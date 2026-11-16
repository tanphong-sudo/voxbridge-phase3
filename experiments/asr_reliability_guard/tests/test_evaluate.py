import copy
import json

import pytest

from experiments.asr_reliability_guard.evaluate import (
    cross_model_diagnostic,
    evaluate_run,
    load_scorer,
    main,
    write_outputs,
)


def fixture_rows():
    manifest = [
        {'id': 'one', 'source_lang': 'en', 'reference_source': 'do not open valve 15', 'wav_sha256': 'a' * 64},
        {'id': 'two', 'source_lang': 'en', 'reference_source': 'hello world', 'wav_sha256': 'b' * 64},
    ]
    rows = [
        {'id': 'one', 'hypothesis': 'do open valve 15', 'audio_sha256': 'a' * 64},
        {'id': 'two', 'hypothesis': 'hello word', 'audio_sha256': 'b' * 64},
    ]
    return manifest, rows


def test_evaluate_counts_wrong_accept_and_inputs_unchanged():
    manifest, rows = fixture_rows()
    original = copy.deepcopy(rows)
    result = evaluate_run(manifest, rows)
    assert result['decisions'] == {'ACCEPT': 1, 'RETRY_ASR': 1, 'ASK_REPEAT': 0}
    assert result['raw_wer'] == pytest.approx(2 / 7)
    assert [r['id'] for r in result['wrong_but_accepted_raw']] == ['two']
    assert result['per_sample'][0]['posthoc_protected_differences']
    assert rows == original
    assert result['per_sample'][0]['guard']['transcript'] == 'do open valve 15'
    assert all(s['confidence'] is None for s in result['per_sample'][0]['guard']['spans'])


@pytest.mark.parametrize('invalid', ['missing', 'duplicate', 'hash'])
def test_strict_coverage_validation(invalid):
    manifest, rows = fixture_rows()
    if invalid == 'missing':
        rows = rows[:1]
    elif invalid == 'duplicate':
        rows = [rows[0], rows[0]]
    else:
        rows[0]['audio_sha256'] = 'f' * 64
    with pytest.raises(ValueError):
        evaluate_run(manifest, rows)


def test_reference_never_passed_to_live_guard():
    manifest, rows = fixture_rows()
    class SpyGuard:
        def __init__(self):
            self.calls = []
        def assess(self, transcript):
            self.calls.append(transcript)
            return {'decision': 'ACCEPT', 'reason': 'fixture', 'spans': []}
    spy = SpyGuard()
    evaluate_run(manifest, rows, guard=spy)
    assert spy.calls == [row['hypothesis'] for row in rows]


def test_vad_skip_retained():
    manifest, rows = fixture_rows()
    rows[1] = {**rows[1], 'hypothesis': '', 'skipped': 'vad_no_speech'}
    result = evaluate_run(manifest, rows)
    assert result['samples'] == 2
    assert result['skipped'] == 1
    assert result['decisions']['ASK_REPEAT'] == 1


def test_cross_model_not_retry_and_hash_mandatory():
    manifest, first = fixture_rows()
    second = copy.deepcopy(first)
    second[0]['hypothesis'] = 'do not open valve 50'
    diagnostic = cross_model_diagnostic(manifest, first, second)
    assert diagnostic['evidence_type'] == 'cross_model_diagnostic_not_same_model_retry'
    assert diagnostic['decisions']['ASK_REPEAT'] == 1
    del second[1]['audio_sha256']
    with pytest.raises(ValueError):
        cross_model_diagnostic(manifest, first, second)


def test_write_outputs_and_load_existing_scorer(tmp_path):
    manifest, rows = fixture_rows()
    result = evaluate_run(manifest, rows)
    write_outputs(tmp_path, {'fixture': result}, {}, [])
    assert json.loads((tmp_path / 'evaluation-summary.json').read_text())['runs']['fixture']['samples'] == 2
    lines = (tmp_path / 'evaluation-fixture.jsonl').read_text().splitlines()
    assert len(lines) == 2
    assert load_scorer().NORMALIZER_VERSION == '1.0.1'


def test_frozen_metric_requires_exact_text_and_coverage():
    manifest, rows = fixture_rows()
    frozen = {'wer_norm': 0.1, 'per_sample': [
        {'id': row['id'], 'reference': source['reference_source'], 'hypothesis': row['hypothesis'],
         'normalized_operations': {'edits': 1}} for source, row in zip(manifest, rows)]}
    assert evaluate_run(manifest, rows, frozen=frozen)['normalized_wer'] == .1
    frozen['per_sample'][0]['hypothesis'] = 'not the same output'
    with pytest.raises(ValueError, match='differs'):
        evaluate_run(manifest, rows, frozen=frozen)
    frozen['per_sample'] = frozen['per_sample'][:1]
    with pytest.raises(ValueError, match='coverage'):
        evaluate_run(manifest, rows, frozen=frozen)


def test_btc_cli_end_to_end_replay_preserves_100_and_writes_report(tmp_path, monkeypatch):
    import experiments.asr_reliability_guard.evaluate as module
    if not (module.EVIDENCE / 'inputs/en100.jsonl').is_file():
        pytest.skip('Optional BTC integration requires local frozen evidence; synthetic tests remain portable')
    monkeypatch.setattr(module, 'EXPERIMENT', tmp_path)
    assert main(['--output', str(tmp_path / 'results')]) == 0
    summary = json.loads((tmp_path / 'results/evaluation-summary.json').read_text())
    assert set(summary['runs']) == {'app', 'small_hf', 'medium', 'turbo', 'parakeet'}
    assert all(run['samples'] == 100 and sum(run['decisions'].values()) == 100
               for run in summary['runs'].values())
    frozen = json.loads((module.EVIDENCE / 'parent/comparison.json').read_text())
    assert summary['runs']['parakeet']['raw_wer'] == frozen['runs']['parakeet']['wer_raw']
    assert summary['same_model_second_decode_available'] is False
    assert 'Không chạy ASR mới' in (tmp_path / 'BTC_RESULTS.md').read_text()


def test_synthetic_evaluation_needs_no_local_evidence(tmp_path, monkeypatch):
    import experiments.asr_reliability_guard.evaluate as module
    monkeypatch.setattr(module, 'EVIDENCE', tmp_path / 'absent')
    module.load_scorer.cache_clear()
    manifest, rows = fixture_rows()
    assert evaluate_run(manifest, rows)['samples'] == 2
    assert 'experiments/asr_reliability_guard' in module.load_scorer().__file__


def test_missing_btc_evidence_fails_with_actionable_message(tmp_path, capsys):
    with pytest.raises(SystemExit) as error:
        main(['--evidence-dir', str(tmp_path / 'not-restored'), '--output', str(tmp_path / 'results')])
    assert error.value.code == 2
    assert 'BTC evidence not available' in capsys.readouterr().err
    assert not (tmp_path / 'results').exists()


def test_external_evidence_cli_runs_100_synthetic_rows_without_repo_artifacts(tmp_path, monkeypatch):
    import hashlib

    import experiments.asr_reliability_guard.evaluate as module
    evidence = tmp_path / 'external-evidence'
    output = tmp_path / 'output'
    report = tmp_path / 'report'
    report.mkdir()
    monkeypatch.setattr(module, 'EXPERIMENT', report)
    manifest = [{'id': f'fixture-{i}', 'source_lang': 'en', 'reference_source': 'hello world',
                 'wav_sha256': 'a' * 64} for i in range(100)]
    rows = [{'id': row['id'], 'hypothesis': 'hello world', 'audio_sha256': 'a' * 64}
            for row in manifest]
    def write_jsonl(path, values):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(''.join(json.dumps(value) + '\n' for value in values))
        return hashlib.sha256(path.read_bytes()).hexdigest()
    manifest_hash = write_jsonl(evidence / 'inputs/en100.jsonl', manifest)
    runs = {}
    for name, relative in module.RUN_FILES.items():
        runs[name] = {'normalizer_version': '1.0.1',
                      'result_file_sha256': write_jsonl(evidence / relative, rows),
                      'wer_norm': 0.0, 'per_sample': [
                          {'id': row['id'], 'reference': 'hello world', 'hypothesis': 'hello world',
                           'normalized_operations': {'edits': 0}} for row in rows]}
    (evidence / 'parent/comparison.json').write_text(json.dumps({
        'manifest_sha256': manifest_hash, 'runs': runs}))
    assert main(['--evidence-dir', str(evidence), '--output', str(output)]) == 0
    summary = json.loads((output / 'evaluation-summary.json').read_text())
    assert all(run['samples'] == 100 and run['raw_wer'] == run['normalized_wer'] == 0.0
               for run in summary['runs'].values())
    assert all(run['decisions']['ACCEPT'] == 100 for run in summary['runs'].values())
    assert (report / 'BTC_RESULTS.md').is_file()
