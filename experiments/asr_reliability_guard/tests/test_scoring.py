"""Portable regression tests for the exact frozen scorer subset."""
import hashlib
import inspect
import json
from pathlib import Path

import pytest

from experiments.asr_reliability_guard import scoring


def test_frozen_function_sources_match_recorded_provenance():
    provenance = json.loads(Path(scoring.__file__).with_name('scoring_provenance.json').read_text())
    assert provenance['normalizer_version'] == scoring.NORMALIZER_VERSION == '1.0.1'
    for name, expected in provenance['function_source_sha256'].items():
        source = inspect.getsource(getattr(scoring, name)).rstrip() + '\n'
        assert hashlib.sha256(source.encode()).hexdigest() == expected


def test_read_jsonl_rejects_nonobjects_and_preserves_blank_lines(tmp_path):
    path = tmp_path / 'rows.jsonl'
    path.write_text('\n{"id": "one"}\n\n')
    assert scoring.read_jsonl(path) == [{'id': 'one'}]
    path.write_text('[]\n')
    with pytest.raises(ValueError, match='expected JSON object'):
        scoring.read_jsonl(path)


def test_raw_words_and_alignment_keep_frozen_rules():
    assert scoring.raw_words("Don't Open MC-07, please.") == ["don't", 'open', 'mc', '07', 'please']
    assert scoring.edit_operations(['not', 'open'], ['open']) == {
        'substitutions': 0, 'deletions': 1, 'insertions': 0,
        'correct': 1, 'reference_units': 2, 'edits': 1,
    }
    assert scoring.edit_operations([], ['open'])['insertions'] == 1
    assert scoring.edit_operations(['open'], ['close'])['substitutions'] == 1


@pytest.mark.parametrize('manifest,rows', [
    ([], []),
    ([{'id': ''}], []),
    ([{'id': 'one'}, {'id': 'one'}], []),
    ([{'id': 'one', 'source_lang': 'vi', 'reference_source': 'one'}], [{'id': 'one', 'hypothesis': 'one'}]),
    ([{'id': 'one', 'source_lang': 'en', 'reference_source': ''}], [{'id': 'one', 'hypothesis': 'one'}]),
])
def test_strict_validation_rejects_invalid_manifests(manifest, rows):
    with pytest.raises(ValueError):
        scoring.validate_pair(manifest, rows)
