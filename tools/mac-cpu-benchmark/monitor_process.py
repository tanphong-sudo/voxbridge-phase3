#!/usr/bin/env python3
"""Run a command while sampling resident memory for its process tree.

The RSS reported by ``ps`` is operating-system resident memory, so it includes
the JVM heap plus native allocations made by ONNX Runtime.  When a JSONL sample
log is supplied, each RSS point is annotated with the number of completed
sample records observed at that instant.  This makes soak-memory trends
reviewable without instrumenting the production runtime.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
from typing import Any


def _percentile(values: list[float], q: float) -> float | None:
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


def _process_table() -> dict[int, tuple[int, int]]:
    output = subprocess.check_output(
        ["ps", "-axo", "pid=,ppid=,rss="],
        text=True,
        stderr=subprocess.DEVNULL,
    )
    table: dict[int, tuple[int, int]] = {}
    for raw in output.splitlines():
        fields = raw.split()
        if len(fields) != 3:
            continue
        try:
            pid, ppid, rss_kib = map(int, fields)
        except ValueError:
            continue
        table[pid] = (ppid, rss_kib)
    return table


def _tree_rss(root_pid: int) -> tuple[int, list[int]]:
    table = _process_table()
    descendants = {root_pid}
    changed = True
    while changed:
        changed = False
        for pid, (ppid, _) in table.items():
            if pid not in descendants and ppid in descendants:
                descendants.add(pid)
                changed = True
    live = sorted(pid for pid in descendants if pid in table)
    return sum(table[pid][1] for pid in live), live


def _completed_samples(path: Path | None) -> int:
    if path is None or not path.is_file():
        return 0
    count = 0
    try:
        with path.open("r", encoding="utf-8") as handle:
            for line in handle:
                if '"type":"sample"' in line or '"type": "sample"' in line:
                    count += 1
    except OSError:
        return count
    return count


def _write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rss-output", type=Path, required=True)
    parser.add_argument("--summary-output", type=Path, required=True)
    parser.add_argument("--sample-log", type=Path)
    parser.add_argument("--interval-seconds", type=float, default=0.25)
    parser.add_argument("--timeout-seconds", type=float, default=900.0)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("command is required after --")
    if args.interval_seconds <= 0 or args.timeout_seconds <= 0:
        parser.error("interval and timeout must be positive")

    args.rss_output.parent.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    process = subprocess.Popen(command, start_new_session=True)
    timed_out = False
    samples: list[dict[str, Any]] = []

    try:
        while True:
            now = time.monotonic()
            elapsed = now - started
            try:
                rss_kib, pids = _tree_rss(process.pid)
            except (OSError, subprocess.SubprocessError):
                rss_kib, pids = 0, []
            samples.append(
                {
                    "elapsed_ms": round(elapsed * 1000),
                    "samples_completed": _completed_samples(args.sample_log),
                    "rss_kib": rss_kib,
                    "pids": ";".join(str(pid) for pid in pids),
                }
            )
            if process.poll() is not None:
                break
            if elapsed >= args.timeout_seconds:
                timed_out = True
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                break
            time.sleep(args.interval_seconds)
    finally:
        return_code = process.wait()

    with args.rss_output.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=["elapsed_ms", "samples_completed", "rss_kib", "pids"],
        )
        writer.writeheader()
        writer.writerows(samples)

    rss_values = [float(row["rss_kib"]) for row in samples if row["rss_kib"] > 0]
    last_seen_pids = [int(pid) for pid in str(samples[-1]["pids"]).split(";") if pid] if samples else []
    try:
        live_pids = set(_process_table())
    except (OSError, subprocess.SubprocessError):
        live_pids = set()
    unreleased = sorted(pid for pid in last_seen_pids if pid in live_pids)
    summary = {
        "command": command,
        "return_code": return_code,
        "timed_out": timed_out,
        "timeout_seconds": args.timeout_seconds,
        "sampling_interval_seconds": args.interval_seconds,
        "samples": len(samples),
        "max_samples_completed": max((int(row["samples_completed"]) for row in samples), default=0),
        "rss_kib": {
            "p50": _percentile(rss_values, 0.50),
            "p95": _percentile(rss_values, 0.95),
            "max": max(rss_values) if rss_values else None,
        },
        "process_tree_released": not unreleased,
        "unreleased_pids": unreleased,
    }
    _write_json(args.summary_output, summary)
    return 124 if timed_out else return_code


if __name__ == "__main__":
    raise SystemExit(main())
