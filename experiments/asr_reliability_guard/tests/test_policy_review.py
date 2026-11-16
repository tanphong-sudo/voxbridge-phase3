"""Regression cases from the independent pre-push review."""
import copy
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier

import pytest

from experiments.asr_reliability_guard.detector import Detector, load_config
from experiments.asr_reliability_guard.feedback import FeedbackStore, Scope
from experiments.asr_reliability_guard.guard import Guard, WordConfidence


@pytest.mark.parametrize('second', ['open please', 'you open please', 'please please open please'])
def test_low_word_placement_does_not_hide_matching_action(second):
    for first, retry in [('please open', second), (second, 'please open')]:
        assert Guard().assess(first, secondary=retry)['decision'] == 'ACCEPT'


@pytest.mark.parametrize('unprotected', ['please ' * 10, 'please you the a ' * 5, 'carefully slowly gently ' * 10])
def test_many_unprotected_matches_cannot_displace_a_protected_anchor(unprotected):
    assert Guard().assess('open ' + unprotected, secondary=unprotected + 'open')['decision'] == 'ACCEPT'
    assert Guard().assess(unprotected + 'open', secondary='open ' + unprotected)['decision'] == 'ACCEPT'


def test_low_only_uncertainty_never_prompts_even_with_low_threshold():
    config = copy.deepcopy(load_config())
    config.update(retry_threshold=.05, ask_after_retry_threshold=.05)
    result = Guard(config).assess('please', [WordConfidence(0, 6, 0)],
                                 confidence_source='test_native', retry_attempted=True)
    assert result['sentence_risk'] == .1
    assert result['decision'] == 'ACCEPT'


@pytest.mark.parametrize('category', ['CRITICAL_NEGATION', 'CRITICAL_NUMBER', 'CRITICAL_ID'])
def test_config_cannot_discount_critical_categories(category):
    config = copy.deepcopy(load_config())
    config['personalization_categories'] = [*config['personalization_categories'], category]
    with pytest.raises(ValueError):
        Guard(config)


def test_config_cannot_unprotect_negation_or_protect_low_words():
    config = copy.deepcopy(load_config())
    config['protected_categories'] = [c for c in config['protected_categories'] if c != 'CRITICAL_NEGATION']
    with pytest.raises(ValueError):
        Guard(config)
    config = copy.deepcopy(load_config())
    config['protected_categories'] = [*config['protected_categories'], 'LOW']
    with pytest.raises(ValueError):
        Guard(config)


def test_sequence_mean_is_not_word_confidence():
    text = 'please do not open valve 15'
    with pytest.raises(ValueError, match='word|token'):
        Guard().assess(text, [WordConfidence(0, len(text), .99)], confidence_source='sequence_mean_logp')


def test_partial_confidence_on_critical_span_does_not_get_accepted_after_retry():
    result = Guard().assess('do not open 15', [WordConfidence(0, 2, .99)],
                           confidence_source='test_native', retry_attempted=True)
    assert result['decision'] == 'ASK_REPEAT'


def test_critical_conflict_wins_despite_many_low_word_matches():
    low = 'please you the a ' * 25
    assert Guard().assess(low + 'not open 15', secondary='open 50 ' + low)['decision'] == 'ASK_REPEAT'


def test_history_cannot_override_rejection_by_supplying_bonus(tmp_path):
    scope = Scope('u', 's', 'm', 'r')
    with FeedbackStore(tmp_path / 'feedback.db') as store:
        store.record(scope, 'open', 'confirm_asr', 'one')
        rejected = store.record(scope, 'open', 'correct_asr', 'two')
    result = Guard().assess('open', [WordConfidence(0, 4, .45)], confidence_source='native',
                           personalization={**rejected, 'reliability_bonus': .15}, scope=scope)
    assert result['decision'] == 'RETRY_ASR'
    assert result['personalization']['reliability_bonus'] == 0


def test_feedback_event_conflicts_are_rejected_under_concurrency(tmp_path):
    path = tmp_path / 'events.db'
    with FeedbackStore(path):
        pass
    barrier = Barrier(2)
    scope = Scope('u', 's', 'm', 'r')

    class ReadBeforeWriteConnection:
        """Deterministically exposes a SELECT-before-INSERT race in the old code."""
        def __init__(self, connection):
            self.connection = connection
            self.wrote = False
        def __enter__(self):
            return self.connection.__enter__()
        def __exit__(self, *args):
            return self.connection.__exit__(*args)
        def close(self):
            self.connection.close()
        def execute(self, sql, args=()):
            cursor = self.connection.execute(sql, args)
            if sql.startswith('INSERT'):
                self.wrote = True
            if sql.startswith('SELECT *') and not self.wrote:
                class SynchronizedCursor:
                    def fetchone(self):
                        row = cursor.fetchone()
                        barrier.wait(timeout=4)
                        return row
                return SynchronizedCursor()
            return cursor

    def record(text):
        with FeedbackStore(path) as store:
            store.connection = ReadBeforeWriteConnection(store.connection)
            try:
                store.record(scope, text, 'confirm_asr', 'same-event')
                return 'stored'
            except ValueError:
                return 'conflict'
    with ThreadPoolExecutor(max_workers=2) as pool:
        assert sorted(pool.map(record, ['open', 'close'])) == ['conflict', 'stored']
    with FeedbackStore(path) as store:
        assert sum(store.evidence(scope, text)['approvals'] for text in ['open', 'close']) == 1


def test_alignment_size_limit_fails_clearly():
    config = copy.deepcopy(load_config())
    config['max_alignment_cells'] = 4
    with pytest.raises(ValueError, match='alignment'):
        Guard(config).assess('open valve 15', secondary='open valve 15')


@pytest.mark.parametrize('key,value', [('approval_prior', True), ('max_input_characters', True)])
def test_config_rejects_boolean_numeric_parameters(key, value):
    config = copy.deepcopy(load_config())
    config[key] = value
    with pytest.raises(ValueError):
        Detector(config)
