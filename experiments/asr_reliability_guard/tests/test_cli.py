import json
import subprocess
import sys

MODULE = 'experiments.asr_reliability_guard.cli'


def run(*args):
    return subprocess.run([sys.executable, '-m', MODULE, *args], check=False, text=True, capture_output=True)


def test_cli_feedback_restart_and_assess(tmp_path):
    db = str(tmp_path / 'feedback.db')
    scope = ('--profile', 'u', '--site', 's', '--model', 'm', '--revision', 'r')
    for event in ['one', 'two']:
        result = run('feedback', 'open', '--db', db, *scope, '--kind', 'confirm_asr', '--event-id', event)
        assert result.returncode == 0, result.stderr
    words = tmp_path / 'native.json'
    words.write_text(json.dumps({'source': 'fixture_native', 'words': [{'start': 0, 'end': 4, 'confidence': .45}]}))
    result = run('assess', 'open', '--db', db, *scope, '--confidence-json', str(words))
    assert result.returncode == 0, result.stderr
    data = json.loads(result.stdout)
    assert data['personalization']['approvals'] == 2
    assert data['decision'] == 'ACCEPT'
    assert data['spans'][0]['confidence'] == .45
    assert json.loads(run('assess', 'open', '--db', db, *scope).stdout)['decision'] == 'RETRY_ASR'


def test_cli_no_feedback_needed_and_errors(tmp_path):
    result = run('assess', 'please check')
    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout)['decision'] == 'ACCEPT'
    assert run('feedback', 'not', '--db', str(tmp_path / 'f.db'), '--kind', 'confirm_asr', '--event-id', 'e').returncode != 0
    bad = tmp_path / 'bad.json'
    bad.write_text('[]')
    assert run('assess', 'not', '--confidence-json', str(bad)).returncode != 0


def test_cli_paired_decode_provenance(tmp_path):
    first = {'transcript': 'do not open 15', 'audio_sha256': 'a' * 64, 'model_id': 'm', 'revision': 'r', 'config_id': 'greedy'}
    second = {**first, 'config_id': 'beam4'}
    a, b = tmp_path / 'a.json', tmp_path / 'b.json'
    a.write_text(json.dumps(first))
    b.write_text(json.dumps(second))
    result = run('retry', '--first', str(a), '--second', str(b))
    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout)['decision'] == 'ACCEPT'
    second['config_id'] = 'greedy'
    b.write_text(json.dumps(second))
    assert run('retry', '--first', str(a), '--second', str(b)).returncode != 0


def test_in_process_cli_assessment_and_errors(capsys, tmp_path):
    import pytest

    from experiments.asr_reliability_guard.cli import main
    assert main(['assess', 'not 15']) == 0
    assert json.loads(capsys.readouterr().out)['decision'] == 'RETRY_ASR'
    with pytest.raises(SystemExit) as error:
        main(['assess', 'not', '--confidence-json', str(tmp_path / 'missing.json')])
    assert error.value.code == 2
    with pytest.raises(SystemExit):
        main(['feedback', 'not', '--kind', 'confirm_asr', '--event-id', 'e'])


def test_in_process_feedback_and_retry(capsys, tmp_path):
    from experiments.asr_reliability_guard.cli import main
    db = str(tmp_path / 'f.db')
    shared = ['--db', db, '--profile', 'u', '--site', 's', '--model', 'm', '--revision', 'r']
    assert main(['feedback', 'open', *shared, '--kind', 'confirm_asr', '--event-id', 'one']) == 0
    assert json.loads(capsys.readouterr().out)['approvals'] == 1
    native = tmp_path / 'n.json'
    native.write_text(json.dumps({'source': 'test_native', 'words': [{'start': 0, 'end': 4, 'confidence': .45}]}))
    assert main(['assess', 'open', *shared, '--confidence-json', str(native)]) == 0
    assert json.loads(capsys.readouterr().out)['decision'] == 'ACCEPT'
    first = {'transcript': 'not 15', 'audio_sha256': 'a' * 64, 'model_id': 'm', 'revision': 'r', 'config_id': 'greedy'}
    a, b = tmp_path / 'a.json', tmp_path / 'b.json'
    a.write_text(json.dumps(first))
    b.write_text(json.dumps({**first, 'config_id': 'beam4'}))
    assert main(['retry', '--first', str(a), '--second', str(b)]) == 0
    assert json.loads(capsys.readouterr().out)['decision'] == 'ACCEPT'
    assert main(['retry', '--first', str(a)]) == 0
    assert json.loads(capsys.readouterr().out)['decision'] == 'RETRY_ASR'
