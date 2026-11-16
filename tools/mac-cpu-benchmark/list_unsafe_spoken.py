#!/usr/bin/env python3
"""List safety-corpus samples that were allowed to speak with oracle slot mismatch."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def load_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def slot_equal(expected, actual) -> bool:
    if isinstance(expected, bool):
        if isinstance(actual, str):
            return actual.upper() == ("HIGH" if expected else "NORMAL")
        return actual is expected
    return expected == actual


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--results", type=Path, required=True)
    args = parser.parse_args()

    manifests = {row["id"]: row for row in load_jsonl(args.manifest)}
    count = 0
    for result in load_jsonl(args.results):
        if result.get("type") != "sample" or not result.get("spoken_allowed", False):
            continue
        manifest = manifests[result["manifest_id"]]
        expected = manifest.get("reference_slots") or {}
        actual = result.get("final_slots") or {}
        mismatches = {
            name: {"expected": value, "actual": actual.get(name)}
            for name, value in expected.items()
            if value is not None and not slot_equal(value, actual.get(name))
        }
        if not mismatches:
            continue
        count += 1
        print(
            json.dumps(
                {
                    "id": result["manifest_id"],
                    "reference_source": manifest.get("reference_source"),
                    "raw_transcript": result.get("raw_transcript"),
                    "decision": result.get("terminal_decision"),
                    "final_text": result.get("final_text"),
                    "mismatches": mismatches,
                    "source_slots": result.get("source_slots"),
                    "final_slots": actual,
                },
                ensure_ascii=False,
                sort_keys=True,
            )
        )
    print(json.dumps({"unsafe_spoken_output": count}))
    return 1 if count else 0


if __name__ == "__main__":
    raise SystemExit(main())
