#!/usr/bin/env python3
"""Evaluate Task-6 quality, hard gates, latency/RTF, and soak RSS evidence."""

from __future__ import annotations

import argparse
import csv
import json
import math
from pathlib import Path
import statistics
import wave
from typing import Any


REJECT_DECISIONS = {"NO_SPEECH", "ASK_REPEAT", "NONE"}


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path}: expected JSON object")
    return value


def load_samples(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, raw in enumerate(handle, 1):
            raw = raw.strip()
            if not raw:
                continue
            value = json.loads(raw)
            if not isinstance(value, dict):
                raise ValueError(f"{path}:{line_number}: expected JSON object")
            if value.get("type", "sample") == "sample":
                rows.append(value)
    return rows


def percentile(values: list[float], q: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * q
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    fraction = position - lower
    return ordered[lower] * (1.0 - fraction) + ordered[upper] * fraction


def stats(values: list[float]) -> dict[str, float | int | None]:
    return {
        "samples": len(values),
        "p50": percentile(values, 0.50),
        "p95": percentile(values, 0.95),
        "max": max(values) if values else None,
    }


def wav_duration_ms(path: str | None) -> float | None:
    if not path:
        return None
    try:
        with wave.open(path, "rb") as handle:
            rate = handle.getframerate()
            return handle.getnframes() * 1000.0 / rate if rate else None
    except (OSError, wave.Error):
        return None


def required_stage_failures(row: dict[str, Any]) -> list[str]:
    timings = row.get("timings_ms")
    if not isinstance(timings, dict):
        return ["missing_timings"]
    missing = [stage for stage in ("read_ms", "vad_ms", "total_ms") if stage not in timings]
    decision = str(row.get("terminal_decision", "NONE"))
    if decision not in REJECT_DECISIONS:
        for stage in ("asr_ms", "decision_ms", "mt_ms"):
            if stage not in timings:
                missing.append(stage)
    return [f"missing_stage:{stage}" for stage in missing]


def performance_summary(rows: list[dict[str, Any]]) -> dict[str, Any]:
    stage_values: dict[str, list[float]] = {}
    cold_rows: list[dict[str, Any]] = []
    warm_rows: list[dict[str, Any]] = []
    seen_directions: set[tuple[str, str]] = set()
    rtfs: list[float] = []

    for row in rows:
        timings = row.get("timings_ms")
        if isinstance(timings, dict):
            for stage, value in timings.items():
                if isinstance(value, (int, float)) and not isinstance(value, bool):
                    stage_values.setdefault(str(stage), []).append(float(value))
        direction = (
            str(row.get("source_lang", row.get("source", "unknown"))),
            str(row.get("target_lang", row.get("target", "unknown"))),
        )
        if direction in seen_directions:
            warm_rows.append(row)
        else:
            cold_rows.append(row)
            seen_directions.add(direction)
        duration = wav_duration_ms(row.get("wav"))
        total_ms = timings.get("total_ms") if isinstance(timings, dict) else None
        if duration and isinstance(total_ms, (int, float)):
            rtfs.append(float(total_ms) / duration)

    def subset_total(subset: list[dict[str, Any]]) -> dict[str, Any]:
        values = [
            float(row["timings_ms"]["total_ms"])
            for row in subset
            if isinstance(row.get("timings_ms"), dict)
            and isinstance(row["timings_ms"].get("total_ms"), (int, float))
        ]
        return stats(values)

    return {
        "stage_latency_ms": {name: stats(values) for name, values in sorted(stage_values.items())},
        "cold_total_ms": subset_total(cold_rows),
        "warm_total_ms": subset_total(warm_rows),
        "rtf": stats(rtfs),
        "cold_definition": "first sample observed for each source->target direction",
        "warm_definition": "all later samples for an already-observed source->target direction",
    }


def rss_summary(path: Path | None, warmup_samples: int) -> dict[str, Any] | None:
    if path is None or not path.is_file():
        return None
    points: list[tuple[int, float]] = []
    with path.open("r", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            try:
                completed = int(row["samples_completed"])
                rss_mib = float(row["rss_kib"]) / 1024.0
            except (KeyError, TypeError, ValueError):
                continue
            if rss_mib > 0:
                points.append((completed, rss_mib))
    warm = [(completed, rss) for completed, rss in points if completed >= warmup_samples]
    rss_values = [rss for _, rss in points]
    warm_values = [rss for _, rss in warm]
    slope_per_turn = None
    if len(warm) >= 2 and len({completed for completed, _ in warm}) >= 2:
        xs = [float(completed) for completed, _ in warm]
        ys = [rss for _, rss in warm]
        xbar = statistics.fmean(xs)
        ybar = statistics.fmean(ys)
        denom = sum((x - xbar) ** 2 for x in xs)
        if denom:
            slope_per_turn = sum((x - xbar) * (y - ybar) for x, y in zip(xs, ys)) / denom
    return {
        "points": len(points),
        "max_samples_completed": max((completed for completed, _ in points), default=0),
        "rss_mib": stats(rss_values),
        "warmup_samples": warmup_samples,
        "warm_rss_mib": stats(warm_values),
        "warm_rss_slope_mib_per_100_turns": slope_per_turn * 100.0 if slope_per_turn is not None else None,
    }


def evaluate(
    rows: list[dict[str, Any]],
    score: dict[str, Any],
    gates: dict[str, Any],
    rss_csv: Path | None,
) -> dict[str, Any]:
    hard_failures: list[dict[str, Any]] = []
    for row in rows:
        sample_id = str(row.get("manifest_id", row.get("id", "unknown")))
        issues: list[str] = []
        if row.get("failure"):
            issues.append(f"failure:{row['failure']}")
        issues.extend(str(item) for item in row.get("gate_failures", []) if item)
        issues.extend(required_stage_failures(row))
        if str(row.get("terminal_decision", "NONE")) in REJECT_DECISIONS and bool(row.get("spoken_allowed", False)):
            issues.append("reject_spoke_translation")
        if str(row.get("terminal_decision", "NONE")) == "REPAIRED_TRANSLATION" and bool(row.get("spoken_allowed", False)):
            issues.append("repaired_spoke_before_confirmation")
        if issues:
            hard_failures.append({"id": sample_id, "issues": sorted(set(issues))})

    quality_cfg = gates["quality"]
    quality_checks: list[dict[str, Any]] = []
    wer_groups = score.get("asr", {}).get("wer", {})
    usable_groups = score.get("asr", {}).get("clean_usable_output", {})
    for lang in quality_cfg.get("languages", []):
        clean_wer = wer_groups.get(lang, {}).get("clean", {}).get("wer")
        usable_rate = usable_groups.get(lang, {}).get("rate")
        quality_checks.append(
            {
                "name": f"clean_wer_{lang}",
                "observed": clean_wer,
                "limit": quality_cfg["clean_wer_max"],
                "passed": clean_wer is not None and clean_wer <= quality_cfg["clean_wer_max"],
            }
        )
        quality_checks.append(
            {
                "name": f"clean_usable_{lang}",
                "observed": usable_rate,
                "limit": quality_cfg["clean_usable_min"],
                "passed": usable_rate is not None and usable_rate >= quality_cfg["clean_usable_min"],
            }
        )

    safety = score.get("safety", {})
    safety_checks = {
        "unsafe_spoken_output": safety.get("unsafe_spoken_output"),
        "unintended_audio_on_reject": safety.get("unintended_audio_on_reject"),
        "repair_auto_spoken": safety.get("repair_auto_spoken"),
    }
    safety_sample_count = int(safety.get("samples") or 0)
    safety_passed = safety_sample_count >= int(gates["hard"]["min_labeled_safety_samples"]) and all(
        value == 0 for value in safety_checks.values()
    )

    warmup_samples = int(gates["performance"].get("rss_warmup_samples", 10))
    rss = rss_summary(rss_csv, warmup_samples)
    hard_passed = not hard_failures
    quality_passed = all(check["passed"] for check in quality_checks)
    return {
        "samples": len(rows),
        "hard": {
            "passed": hard_passed,
            "failure_count": len(hard_failures),
            "failures": hard_failures,
        },
        "quality": {"passed": quality_passed, "checks": quality_checks},
        "safety": {
            "passed": safety_passed,
            "labeled_samples": safety_sample_count,
            "checks": safety_checks,
            "note": "Safety gate is only authoritative when the score file comes from the labeled safety suite.",
        },
        "performance": performance_summary(rows),
        "rss": rss,
        "overall_passed": hard_passed and quality_passed and safety_passed,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--score", type=Path, required=True)
    parser.add_argument("--gates", type=Path, required=True)
    parser.add_argument("--rss-csv", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = evaluate(
        load_samples(args.results),
        load_json(args.score),
        load_json(args.gates),
        args.rss_csv,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(output, indent=2, sort_keys=True))
    return 0 if output["overall_passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
