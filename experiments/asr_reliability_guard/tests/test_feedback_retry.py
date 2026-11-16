import pytest

from experiments.asr_reliability_guard.feedback import FeedbackStore, Scope
from experiments.asr_reliability_guard.guard import Guard, WordConfidence
from experiments.asr_reliability_guard.retry import DecodePass, run_selective


def test_explicit_confirmations_increase_bounded_reliability(tmp_path):
    scope = Scope('operator-a', 'site-a', 'whisper-small', 'revision-1')
    with FeedbackStore(tmp_path / 'feedback.sqlite') as store:
        before = store.evidence(scope, 'open')
        store.record(scope, 'open', 'confirm_asr', 'event-1')
        once = store.evidence(scope, 'OPEN!')
        store.record(scope, 'open', 'confirm_asr', 'event-2')
        twice = store.evidence(scope, 'open')
        assert 0 == before['reliability_bonus'] < once['reliability_bonus'] < twice['reliability_bonus'] <= .15
        assert store.evidence(Scope('operator-b', 'site-a', 'whisper-small', 'revision-1'), 'open')['approvals'] == 0
        assert store.evidence(scope, 'do not open')['approvals'] == 0
        assert store.evidence(scope, 'open 15')['approvals'] == 0
        native = [WordConfidence(0, 4, .45)]
        assert Guard().assess('open', native, confidence_source='test_native')['decision'] == 'RETRY_ASR'
        result = Guard().assess('open', native, confidence_source='test_native', personalization=twice, scope=scope)
        assert result['decision'] == 'ACCEPT'
        assert result['spans'][0]['confidence'] == .45
        assert Guard().assess('open', personalization=twice, scope=scope)['decision'] == 'RETRY_ASR'
    with FeedbackStore(tmp_path / 'feedback.sqlite') as store:
        assert store.evidence(scope, 'open')['approvals'] == 2
        store.record(scope, 'open', 'reject_asr', 'event-3')
        assert store.evidence(scope, 'open')['reliability_bonus'] == 0


def test_feedback_idempotence_scope_and_translation_not_asr(tmp_path):
    scope = Scope('u', 's', 'm', 'r')
    with FeedbackStore(tmp_path / 'f.db') as store:
        store.record(scope, 'not 15', 'confirm_asr', 'one')
        store.record(scope, 'not 15', 'confirm_asr', 'one')
        assert store.evidence(scope, 'not 15')['approvals'] == 1
        with pytest.raises(ValueError):
            store.record(scope, 'not 50', 'confirm_asr', 'one')
        with pytest.raises(ValueError):
            store.record(scope, 'not 15', 'approve_translation', 'two')
        assert store.evidence(scope, 'not 50')['approvals'] == 0
        bonus = store.evidence(scope, 'not 15')
        result = Guard().assess('not 15', secondary='now 50', personalization=bonus, scope=scope)
        assert result['decision'] == 'ASK_REPEAT'
        assert Guard().assess('not 15', personalization=bonus, scope=scope)['decision'] == 'RETRY_ASR'
        words = [WordConfidence(0, 3, .55), WordConfidence(4, 6, .99)]
        assert Guard().assess('not 15', words, confidence_source='native', personalization=bonus, scope=scope)['decision'] == 'RETRY_ASR'


def test_retry_same_audio_different_configuration_and_selective_call():
    first = DecodePass('do not open 15', 'a' * 64, 'm', 'r', 'greedy')
    calls = []
    def decode():
        calls.append(True)
        return DecodePass('do not open 15', 'a' * 64, 'm', 'r', 'beam-4')
    result = run_selective(first, decode)
    assert calls == [True]
    assert result['decision'] == 'ACCEPT'
    calls.clear()
    assert run_selective(DecodePass('please check', 'a' * 64, 'm', 'r', 'greedy'), decode)['decision'] == 'ACCEPT'
    assert not calls


@pytest.mark.parametrize('field,value', [('audio_sha256', 'b' * 64), ('model_id', 'other'), ('revision', 'other'), ('config_id', 'greedy')])
def test_retry_rejects_invalid_provenance(field, value):
    from dataclasses import replace
    first = DecodePass('not 15', 'a' * 64, 'm', 'r', 'greedy')
    with pytest.raises(ValueError):
        run_selective(first, lambda: replace(first, **{field: value}))


def test_retry_unavailable_and_failure_are_visible():
    first = DecodePass('not 15', 'a' * 64, 'm', 'r', 'greedy')
    assert run_selective(first, None)['decision'] == 'RETRY_ASR'
    def failed():
        raise RuntimeError('decoder unavailable')
    result = run_selective(first, failed)
    assert result['decision'] == 'ASK_REPEAT'
    assert result['retry_error'] == 'RuntimeError'


def test_invalid_scope():
    with pytest.raises(ValueError):
        Scope('', 's', 'm', 'r')


def test_retry_does_not_hide_low_native_confidence_on_second_pass():
    first = DecodePass('not 15', 'a' * 64, 'm', 'r', 'greedy')
    second = DecodePass('not 15', 'a' * 64, 'm', 'r', 'beam4',
                        (WordConfidence(0, 3, .01), WordConfidence(4, 6, .01)), 'native')
    result = run_selective(first, lambda: second)
    assert result['transcript'] == first.transcript
    assert result['decision'] == 'ASK_REPEAT'


def test_guard_rejects_history_for_wrong_phrase_or_scope(tmp_path):
    scope = Scope('u', 's', 'm', 'r')
    with FeedbackStore(tmp_path / 'f.db') as store:
        history = store.record(scope, 'open', 'confirm_asr', 'event')
    for text, expected_scope in [('close', scope), ('open', Scope('other', 's', 'm', 'r')), ('open', None)]:
        with pytest.raises(ValueError):
            Guard().assess(text, [WordConfidence(0, len(text), .45)], confidence_source='native',
                           personalization=history, scope=expected_scope)


@pytest.mark.parametrize('model,revision', [('new-model', 'r'), ('m', 'new-revision')])
def test_retry_binds_feedback_scope_to_actual_decode_model(tmp_path, model, revision):
    scope = Scope('u', 's', 'm', 'r')
    with FeedbackStore(tmp_path / 'f.db') as store:
        evidence = store.record(scope, 'open', 'confirm_asr', 'event')
    first = DecodePass('open', 'a' * 64, model, revision, 'greedy', (WordConfidence(0, 4, .45),), 'native')
    with pytest.raises(ValueError):
        run_selective(first, personalization=evidence, scope=scope)
