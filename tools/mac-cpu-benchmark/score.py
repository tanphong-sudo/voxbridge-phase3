#!/usr/bin/env python3
"""Independent scorer for the VoxBridge Mac CPU benchmark.

Ground-truth fields live in the manifest and are joined only after inference has
completed.  The scorer intentionally uses only the Python standard library so
it can run offline on a clean Mac without adding another benchmark dependency.

Text normalization used for ASR metrics:
  * Unicode NFKC
  * Unicode-aware casefold
  * punctuation/symbols -> spaces
  * whitespace collapsed

WER is corpus edit distance / corpus reference-word count. Vietnamese CER uses
the same normalization and then removes spaces before character scoring.

BLEU is unsmoothed corpus BLEU-4 with clipped n-gram precision. chrF is the
macro average of character n-gram F-beta (n=1..6, beta=2), after the same text
normalization. They are emitted only for rows whose manifest explicitly marks
the target reference human_verified=true.
"""

from __future__ import annotations

import argparse
import collections
import json
import math
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Sequence


REJECT_DECISIONS = {"NO_SPEECH", "ASK_REPEAT", "NONE"}


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            line = line.strip()
            if not line:
                continue
            value = json.loads(line)
            if not isinstance(value, dict):
                raise ValueError(f"{path}:{line_number}: expected JSON object")
            rows.append(value)
    return rows


def normalize_text(text: str | None) -> str:
    if not text:
        return ""
    text = unicodedata.normalize("NFKC", text).casefold()
    chars: list[str] = []
    for char in text:
        category = unicodedata.category(char)
        chars.append(" " if category[0] in {"P", "S", "Z"} else char)
    return " ".join("".join(chars).split())


def tokens(text: str | None) -> list[str]:
    normalized = normalize_text(text)
    return normalized.split() if normalized else []


def vi_characters(text: str | None) -> list[str]:
    return list(normalize_text(text).replace(" ", ""))


def edit_distance(reference: Sequence[Any], hypothesis: Sequence[Any]) -> int:
    previous = list(range(len(hypothesis) + 1))
    for i, ref_item in enumerate(reference, 1):
        current = [i]
        for j, hyp_item in enumerate(hypothesis, 1):
            current.append(
                min(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + (ref_item != hyp_item),
                )
            )
        previous = current
    return previous[-1]


@dataclass
class ErrorAccumulator:
    edits: int = 0
    reference_units: int = 0
    samples: int = 0
    empty_reference_samples: int = 0

    def add(self, reference: Sequence[Any], hypothesis: Sequence[Any]) -> None:
        self.samples += 1
        if not reference:
            self.empty_reference_samples += 1
            return
        self.edits += edit_distance(reference, hypothesis)
        self.reference_units += len(reference)

    def value(self) -> float | None:
        if self.reference_units == 0:
            return None
        return self.edits / self.reference_units

    def as_dict(self, metric: str) -> dict[str, Any]:
        return {
            metric: self.value(),
            "edits": self.edits,
            "reference_units": self.reference_units,
            "samples": self.samples,
            "empty_reference_samples": self.empty_reference_samples,
        }


def _ngrams(items: Sequence[str], n: int) -> collections.Counter[tuple[str, ...]]:
    return collections.Counter(tuple(items[i : i + n]) for i in range(len(items) - n + 1))


def corpus_bleu(references: Sequence[str], hypotheses: Sequence[str]) -> float | None:
    if not references or len(references) != len(hypotheses):
        return None
    clipped = [0, 0, 0, 0]
    totals = [0, 0, 0, 0]
    ref_len = 0
    hyp_len = 0
    for reference, hypothesis in zip(references, hypotheses):
        ref = tokens(reference)
        hyp = tokens(hypothesis)
        ref_len += len(ref)
        hyp_len += len(hyp)
        for n in range(1, 5):
            ref_counts = _ngrams(ref, n)
            hyp_counts = _ngrams(hyp, n)
            totals[n - 1] += sum(hyp_counts.values())
            clipped[n - 1] += sum(min(count, ref_counts[gram]) for gram, count in hyp_counts.items())
    if ref_len == 0 or hyp_len == 0:
        return 0.0
    precisions: list[float] = []
    for numerator, denominator in zip(clipped, totals):
        if denominator == 0 or numerator == 0:
            return 0.0
        precisions.append(numerator / denominator)
    brevity = 1.0 if hyp_len > ref_len else math.exp(1.0 - ref_len / hyp_len)
    return 100.0 * brevity * math.exp(sum(math.log(p) for p in precisions) / 4.0)


def corpus_chrf(
    references: Sequence[str],
    hypotheses: Sequence[str],
    *,
    max_order: int = 6,
    beta: float = 2.0,
) -> float | None:
    if not references or len(references) != len(hypotheses):
        return None
    beta2 = beta * beta
    per_order: list[float] = []
    normalized = [
        (normalize_text(reference).replace(" ", ""), normalize_text(hypothesis).replace(" ", ""))
        for reference, hypothesis in zip(references, hypotheses)
    ]
    for n in range(1, max_order + 1):
        matched = 0
        ref_total = 0
        hyp_total = 0
        for reference, hypothesis in normalized:
            ref_counts = _ngrams(list(reference), n)
            hyp_counts = _ngrams(list(hypothesis), n)
            ref_total += sum(ref_counts.values())
            hyp_total += sum(hyp_counts.values())
            matched += sum(min(count, hyp_counts[gram]) for gram, count in ref_counts.items())
        if ref_total == 0 and hyp_total == 0:
            continue
        precision = matched / hyp_total if hyp_total else 0.0
        recall = matched / ref_total if ref_total else 0.0
        denominator = beta2 * precision + recall
        per_order.append((1.0 + beta2) * precision * recall / denominator if denominator else 0.0)
    if not per_order:
        return 0.0
    return 100.0 * sum(per_order) / len(per_order)


def normalized_word_edit_rate(reference: str | None, hypothesis: str | None) -> float | None:
    ref = tokens(reference)
    if not ref:
        return None
    return edit_distance(ref, tokens(hypothesis)) / len(ref)


def _lang(row: dict[str, Any]) -> str:
    value = row.get("source_lang", row.get("source", "unknown"))
    return str(value).lower()


def _condition(row: dict[str, Any]) -> str:
    snr = row.get("snr_db")
    if snr is None:
        return "clean"
    value = float(snr)
    return f"snr_{value:g}db"


def _slot_equal(expected: Any, actual: Any, name: str | None = None) -> bool:
    if name == "urgency":
        def canonical(value: Any) -> str | None:
            if isinstance(value, bool):
                return "high" if value else "normal"
            if isinstance(value, str) and value.casefold() in {"high", "normal"}:
                return value.casefold()
            return None
        normalized = canonical(expected)
        return normalized is not None and normalized == canonical(actual)
    if isinstance(expected, str) and isinstance(actual, str):
        return expected.casefold() == actual.casefold()
    return expected == actual


@dataclass
class SafetyAccumulator:
    samples: int = 0
    slot_total: int = 0
    slot_correct: int = 0
    unsafe_spoken: int = 0
    false_repair: int = 0
    false_refusal: int = 0
    actual_spoken: int = 0
    unintended_reject_audio: int = 0
    repaired_auto_spoken: int = 0


@dataclass
class MemoryAccumulator:
    eligible: int = 0
    accepted: int = 0
    edit_rates: list[float] = field(default_factory=list)


def score_rows(manifest_rows: list[dict[str, Any]], result_rows: list[dict[str, Any]]) -> dict[str, Any]:
    manifest_ids: set[str] = set()
    for row in manifest_rows:
        key = row.get("id")
        if not isinstance(key, str) or not key.strip():
            raise ValueError("manifest sample requires a nonempty string id")
        if key in manifest_ids:
            raise ValueError(f"duplicate manifest id: {key}")
        manifest_ids.add(key)
    results: dict[str, dict[str, Any]] = {}
    for row in result_rows:
        kind = row.get("type", "sample")
        if kind == "summary":
            continue
        if kind != "sample":
            raise ValueError(f"unsupported result row type: {kind!r}")
        raw_key = row.get("manifest_id", row.get("id"))
        if not isinstance(raw_key, str) or not raw_key.strip():
            raise ValueError("result sample requires a nonempty string manifest_id or id")
        if raw_key not in manifest_ids:
            raise ValueError(f"result id not in manifest: {raw_key}")
        if raw_key in results:
            raise ValueError(f"duplicate result id: {raw_key}")
        if "spoken_allowed" in row and not isinstance(row["spoken_allowed"], bool):
            raise ValueError(f"{raw_key}: spoken_allowed must be boolean")
        issues = row.get("gate_failures", [])
        if not isinstance(issues, list) or not all(isinstance(issue, str) for issue in issues):
            raise ValueError(f"{raw_key}: gate_failures must be a list of strings")
        results[raw_key] = row

    wer_groups: dict[tuple[str, str], ErrorAccumulator] = collections.defaultdict(ErrorAccumulator)
    cer_groups: dict[tuple[str, str], ErrorAccumulator] = collections.defaultdict(ErrorAccumulator)
    verified_mt: dict[str, dict[str, list[str]]] = {
        "reference_transcript": {"references": [], "hypotheses": []},
        "asr_transcript": {"references": [], "hypotheses": []},
    }
    mt_path_drift: list[float] = []
    safety = SafetyAccumulator()
    memory = MemoryAccumulator()
    missing_results: list[str] = []
    failures: list[dict[str, Any]] = []
    usable_clean = collections.Counter()
    clean_total = collections.Counter()

    for manifest in manifest_rows:
        sample_id = str(manifest.get("id"))
        result = results.get(sample_id)
        if result is None:
            missing_results.append(sample_id)
            continue

        issues = list(result.get("gate_failures") or [])
        decision = str(result.get("terminal_decision", "NONE"))
        spoken_allowed = bool(result.get("spoken_allowed", False))
        # Output-policy invariants hold for every sample, even without oracle safety slots.
        if decision in REJECT_DECISIONS and spoken_allowed:
            safety.unintended_reject_audio += 1
            issues.append("unintended_audio_on_reject")
        if decision == "REPAIRED_TRANSLATION" and spoken_allowed:
            safety.repaired_auto_spoken += 1
            issues.append("repair_auto_spoken")

        lang = _lang(manifest)
        condition = _condition(manifest)
        reference_source = manifest.get("reference_source")
        transcript = result.get("raw_transcript")
        if isinstance(reference_source, str) and reference_source.strip():
            wer_groups[(lang, condition)].add(tokens(reference_source), tokens(transcript))
            if lang == "vi":
                cer_groups[(lang, condition)].add(vi_characters(reference_source), vi_characters(transcript))

            if condition == "clean":
                clean_total[lang] += 1
                decision = str(result.get("terminal_decision", "NONE"))
                if decision not in REJECT_DECISIONS and bool(result.get("final_text")):
                    usable_clean[lang] += 1

        ref_mt = result.get("mt_from_reference_text")
        asr_mt = result.get("mt_from_asr_text", result.get("mt_text"))
        if isinstance(ref_mt, str) and isinstance(asr_mt, str):
            rate = normalized_word_edit_rate(ref_mt, asr_mt)
            if rate is not None:
                mt_path_drift.append(rate)

        reference_target = manifest.get("reference_target")
        if manifest.get("human_verified") is True and isinstance(reference_target, str) and reference_target.strip():
            if isinstance(ref_mt, str) and ref_mt.strip():
                verified_mt["reference_transcript"]["references"].append(reference_target)
                verified_mt["reference_transcript"]["hypotheses"].append(ref_mt)
            if isinstance(asr_mt, str) and asr_mt.strip():
                verified_mt["asr_transcript"]["references"].append(reference_target)
                verified_mt["asr_transcript"]["hypotheses"].append(asr_mt)

        reference_slots = manifest.get("reference_slots")
        if isinstance(reference_slots, dict) and reference_slots:
            safety.samples += 1
            final_slots = result.get("final_slots", result.get("candidate_slots"))
            final_slots = final_slots if isinstance(final_slots, dict) else {}
            mismatch = False
            for name, expected in reference_slots.items():
                if expected is None:
                    continue
                safety.slot_total += 1
                if name in final_slots and _slot_equal(expected, final_slots[name], name):
                    safety.slot_correct += 1
                else:
                    mismatch = True

            safety.actual_spoken += int(spoken_allowed)
            if spoken_allowed and mismatch:
                safety.unsafe_spoken += 1
                issues.append("unsafe_spoken_output")

            pre_repair_slots = result.get("pre_repair_slots")
            if decision == "REPAIRED_TRANSLATION" and isinstance(pre_repair_slots, dict):
                if all(
                    expected is None or _slot_equal(expected, pre_repair_slots.get(name), name)
                    for name, expected in reference_slots.items()
                ):
                    safety.false_repair += 1

            allowed = manifest.get("allowed_decisions")
            if decision in REJECT_DECISIONS:
                if isinstance(allowed, list):
                    safety.false_refusal += int(decision not in allowed)
                elif isinstance(reference_source, str) and reference_source.strip():
                    safety.false_refusal += 1

        memory_eligible = result.get("memory_eligible")
        if memory_eligible is True:
            memory.eligible += 1
            accepted = result.get("memory_accepted")
            if accepted is None:
                accepted = result.get("terminal_decision") == "MEMORY_TRANSLATION"
            memory.accepted += int(bool(accepted))
        edit_rate = result.get("memory_edit_rate")
        if isinstance(edit_rate, (int, float)):
            memory.edit_rates.append(float(edit_rate))
        elif result.get("memory_query_text") is not None and result.get("memory_source_text") is not None:
            derived = normalized_word_edit_rate(str(result["memory_source_text"]), str(result["memory_query_text"]))
            if derived is not None:
                memory.edit_rates.append(derived)

        if result.get("failure") or issues:
            failures.append(
                {
                    "id": sample_id,
                    "failure": result.get("failure"),
                    "gate_failures": sorted(set(issues)),
                }
            )

    def metric_groups(groups: dict[tuple[str, str], ErrorAccumulator], metric: str) -> dict[str, Any]:
        output: dict[str, Any] = {}
        for (lang, condition), accumulator in sorted(groups.items()):
            output.setdefault(lang, {})[condition] = accumulator.as_dict(metric)
        return output

    mt_metrics: dict[str, Any] = {}
    for path_name, values in verified_mt.items():
        refs = values["references"]
        hyps = values["hypotheses"]
        mt_metrics[path_name] = {
            "human_verified_samples": len(refs),
            "bleu": corpus_bleu(refs, hyps) if refs else None,
            "chrf": corpus_chrf(refs, hyps) if refs else None,
        }
    mt_metrics["reference_vs_asr_path"] = {
        "samples": len(mt_path_drift),
        "mean_word_edit_rate": (sum(mt_path_drift) / len(mt_path_drift)) if mt_path_drift else None,
    }

    return {
        "normalization": {
            "unicode": "NFKC",
            "case": "casefold",
            "punctuation_symbols": "space",
            "whitespace": "collapsed",
            "vi_cer": "normalized text with spaces removed",
            "safety_urgency": "true=HIGH, false=NORMAL; missing/unknown values do not match",
        },
        "coverage": {
            "manifest_samples": len(manifest_rows),
            "result_samples": len(results),
            "missing_result_count": len(missing_results),
            "missing_result_ids": missing_results,
        },
        "asr": {
            "wer": metric_groups(wer_groups, "wer"),
            "vi_cer": metric_groups(cer_groups, "cer").get("vi", {}),
            "clean_usable_output": {
                lang: {
                    "usable": usable_clean[lang],
                    "total": clean_total[lang],
                    "rate": usable_clean[lang] / clean_total[lang] if clean_total[lang] else None,
                }
                for lang in sorted(clean_total)
            },
        },
        "mt": mt_metrics,
        "safety": {
            "samples": safety.samples,
            "slot_total": safety.slot_total,
            "slot_correct": safety.slot_correct,
            "slot_accuracy": safety.slot_correct / safety.slot_total if safety.slot_total else None,
            "unsafe_spoken_output": safety.unsafe_spoken,
            "false_repair": safety.false_repair,
            "false_refusal": safety.false_refusal,
            "actual_spoken_permission": safety.actual_spoken,
            "unintended_audio_on_reject": safety.unintended_reject_audio,
            "repair_auto_spoken": safety.repaired_auto_spoken,
        },
        "memory": {
            "eligible": memory.eligible,
            "accepted": memory.accepted,
            "acceptance_rate": memory.accepted / memory.eligible if memory.eligible else None,
            "edit_rate_samples": len(memory.edit_rates),
            "mean_edit_rate": sum(memory.edit_rates) / len(memory.edit_rates) if memory.edit_rates else None,
        },
        "failures": failures,
    }


def _write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
                    encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--failures", type=Path)
    args = parser.parse_args()

    try:
        summary = score_rows(load_jsonl(args.manifest), load_jsonl(args.results))
    except (ValueError, OSError) as error:
        parser.error(str(error))
    if args.output:
        _write_json(args.output, summary)
    if args.failures:
        _write_jsonl(args.failures, summary["failures"])
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    return 1 if summary["failures"] or summary["coverage"]["missing_result_count"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
