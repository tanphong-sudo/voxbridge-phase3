"""Exact frozen raw-scoring/validation subset, tracked for portable offline tests.

Copied unchanged from the English scorer recorded in scoring_provenance.json.
Normalized WER is never approximated here; evaluation reuses verified frozen metrics.
"""
import json
import math
from pathlib import Path
import re
import unicodedata

NORMALIZER_VERSION = "1.0.1"


def read_jsonl(path):
    rows = []
    for line_number, line in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        row = json.loads(line)
        if not isinstance(row, dict):
            raise ValueError(f"{path}:{line_number}: expected JSON object")
        rows.append(row)
    return rows


def raw_words(text):
    text = unicodedata.normalize("NFC", text.lower())
    return re.sub(r"[^\w\s']", " ", text).split()


def edit_operations(reference, hypothesis):
    """Levenshtein alignment; ties prefer substitution, then deletion, then insertion."""
    n, m = len(reference), len(hypothesis)
    distances = [list(range(m + 1))] + [[i] + [0] * m for i in range(1, n + 1)]
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            distances[i][j] = min(
                distances[i - 1][j - 1] + (reference[i - 1] != hypothesis[j - 1]),
                distances[i - 1][j] + 1,
                distances[i][j - 1] + 1,
            )
    substitutions = deletions = insertions = correct = 0
    i, j = n, m
    while i or j:
        if i and j and distances[i][j] == distances[i - 1][j - 1] + (reference[i - 1] != hypothesis[j - 1]):
            if reference[i - 1] == hypothesis[j - 1]:
                correct += 1
            else:
                substitutions += 1
            i, j = i - 1, j - 1
        elif i and distances[i][j] == distances[i - 1][j] + 1:
            deletions += 1
            i -= 1
        else:
            insertions += 1
            j -= 1
    return {"substitutions": substitutions, "deletions": deletions,
            "insertions": insertions, "correct": correct,
            "reference_units": n, "edits": substitutions + deletions + insertions}


def index_rows(rows, label):
    indexed = {}
    for row in rows:
        identifier = row.get("id")
        if not isinstance(identifier, str) or not identifier.strip():
            raise ValueError(f"Invalid {label} ID: {identifier!r}")
        if identifier in indexed:
            raise ValueError(f"Duplicate {label} ID: {identifier}")
        indexed[identifier] = row
    return indexed


def validate_pair(manifest, rows):
    expected, actual = index_rows(manifest, "manifest"), index_rows(rows, "result")
    if not expected:
        raise ValueError("Manifest is empty")
    if set(expected) != set(actual):
        raise ValueError(f"Result coverage mismatch: missing={sorted(set(expected)-set(actual))}, unexpected={sorted(set(actual)-set(expected))}")
    for identifier, source in expected.items():
        if source.get("source_lang") != "en":
            raise ValueError(f"Expected English manifest row: {identifier}")
        reference = source.get("reference_source")
        if not isinstance(reference, str) or not reference.strip():
            raise ValueError(f"Invalid reference for {identifier}")
        row = actual[identifier]
        if row.get("reference", reference) != reference:
            raise ValueError(f"Result reference differs from frozen manifest: {identifier}")
        if not isinstance(row.get("hypothesis"), str):
            raise ValueError(f"Invalid hypothesis for {identifier}")
        if row.get("error") and row["hypothesis"].strip():
            raise ValueError(f"Result has error and nonempty hypothesis: {identifier}")
        for key in ("audio_sha256", "wav_sha256"):
            if row.get(key) is not None and row[key] != source.get("wav_sha256"):
                raise ValueError(f"Result audio hash differs from frozen manifest: {identifier}")
        latency = row.get("asr_ms")
        if latency is not None and (isinstance(latency, bool) or not isinstance(latency, (int, float)) or not math.isfinite(latency) or latency < 0):
            raise ValueError(f"Invalid latency for {identifier}: {latency!r}")
        if "cold_start" in row and not isinstance(row["cold_start"], bool):
            raise ValueError(f"Invalid cold_start for {identifier}")
    for key in ("model_id", "revision", "precision", "backend"):
        values = {str(row[key]) for row in rows if row.get(key) is not None}
        if len(values) > 1:
            raise ValueError(f"Mixed {key} provenance in a single run: {sorted(values)}")
    return expected, actual
