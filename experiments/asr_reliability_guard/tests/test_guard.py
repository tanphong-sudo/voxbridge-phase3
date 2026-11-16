import copy
import math

import pytest

from experiments.asr_reliability_guard.detector import Detector, load_config
from experiments.asr_reliability_guard.guard import Guard, WordConfidence


@pytest.mark.parametrize(('text', 'expected'), [
    ('Please do not open valve 15', [('Please', 'LOW'), ('do not open', 'CRITICAL_NEGATION'), ('15', 'CRITICAL_NUMBER')]),
    ('Never touch conveyor B3', [('Never touch', 'CRITICAL_NEGATION'), ('B3', 'CRITICAL_ID')]),
    ('Do not enter', [('Do not enter', 'CRITICAL_NEGATION')]),
    ('You can open valve 3', [('You', 'LOW'), ('open', 'HIGH_ACTION'), ('3', 'CRITICAL_NUMBER')]),
    ('You cannot open valve 3', [('cannot', 'CRITICAL_NEGATION')]),
    ('Set the pressure to 3.5 bar', [('3.5 bar', 'CRITICAL_QUANTITY')]),
    ('Please check the machine', [('Please', 'LOW'), ('machine', 'HIGH_EQUIPMENT')]),
    ('No one should enter this area', [('No one', 'CRITICAL_NEGATION')]),
    ('The system is not ready', [('not', 'CRITICAL_NEGATION')]),
    ('Work without removing the guard', [('without', 'CRITICAL_NEGATION')]),
    ('MC-07 runs at 220 V', [('MC-07', 'CRITICAL_ID'), ('220 V', 'CRITICAL_QUANTITY')]),
    ('DON’T open it', [('DON’T', 'CRITICAL_NEGATION')]),
    ('Start at 8.30am.', [('8.30am', 'CRITICAL_NUMBER')]),
])
def test_required_detection(text, expected):
    spans = Detector().detect(text)
    assert set(expected) <= {(s.text, s.category) for s in spans}
    assert all(text[s.start:s.end] == s.text for s in spans)


def test_configurable_glossary_and_no_vocabulary_in_algorithm():
    config = copy.deepcopy(load_config())
    config['lexicons']['CRITICAL_GLOSSARY'] = ['flux capacitor']
    assert Detector(config).detect('Flux capacitor')[0].category == 'CRITICAL_GLOSSARY'
    config['lexicons']['CRITICAL_NEGATION'] = []
    assert Detector(config).detect('not')[0].category == 'NORMAL'


def confidences(text, low=None):
    import re
    return [WordConfidence(m.start(), m.end(), 0.05 if m.group().lower() == low else 0.99)
            for m in re.finditer(r'\S+', text)]


def test_low_uncertainty_is_accepted_and_confidence_not_modified():
    text = 'please do not open valve 15'
    result = Guard().assess(text, confidences(text, 'please'), confidence_source='test_native')
    assert result['decision'] == 'ACCEPT'
    assert result['spans'][0]['confidence'] == 0.05
    assert result['sentence_risk'] == pytest.approx(0.095)


def test_critical_uncertainty_not_averaged_away():
    text = 'please do not open valve 15 ' + 'carefully ' * 80
    result = Guard().assess(text, confidences(text, 'not'), confidence_source='test_native')
    assert result['decision'] == 'RETRY_ASR'
    assert result['sentence_risk'] == pytest.approx(0.95)


def test_missing_is_null_and_selective():
    result = Guard().assess('please do not open valve 15')
    assert result['decision'] == 'RETRY_ASR'
    assert result['sentence_risk'] is None
    assert all(s['confidence'] is None for s in result['spans'])
    assert Guard().assess('Please check this')['decision'] == 'ACCEPT'
    assert Guard().assess('open valve', retry_attempted=True)['decision'] == 'ASK_REPEAT'
    assert Guard().assess('')['decision'] == 'ASK_REPEAT'


@pytest.mark.parametrize('secondary', [
    'Do now open valve 50', 'Do open valve 15', 'Do not close valve 15',
    'Do not open valve 15 or 50', 'Do not open valve B3',
])
def test_disagreement_both_directions(secondary):
    for first, second in [('Do not open valve 15', secondary), (secondary, 'Do not open valve 15')]:
        result = Guard().assess(first, secondary=second)
        assert result['decision'] == 'ASK_REPEAT'
        assert result['disagreements']


def test_agreement_is_categorical_evidence_not_fabricated_confidence():
    result = Guard().assess('Please do not open valve 15', secondary='do not open valve 15')
    assert result['decision'] == 'ACCEPT'
    assert all(s['confidence'] is None for s in result['spans'])
    assert result['signal'] == 'decode_agreement_heuristic'


def test_alignment_catches_reordering_even_when_same_critical_words():
    result = Guard().assess('open valve 15 then close valve 50', secondary='open valve 50 then close valve 15')
    assert result['decision'] == 'ASK_REPEAT'


@pytest.mark.parametrize('first,second', [('Set pressure 3.5%', 'Set pressure 3.5'), ('Set to 30 °C', 'Set to 30 C')])
def test_quantity_symbol_loss_is_protected(first, second):
    assert Guard().assess(first, secondary=second)['decision'] == 'ASK_REPEAT'
    assert Guard().assess(second, secondary=first)['decision'] == 'ASK_REPEAT'


@pytest.mark.parametrize('value', [-0.1, 1.1, math.nan, math.inf, True])
def test_invalid_confidence(value):
    with pytest.raises(ValueError):
        WordConfidence(0, 3, value)


def test_invalid_offsets_source_and_partial_span():
    for words in [[WordConfidence(0, 99, .9)], [WordConfidence(0, 3, .9), WordConfidence(1, 3, .9)]]:
        with pytest.raises(ValueError):
            Guard().assess('not', words, confidence_source='native')
    with pytest.raises(ValueError):
        Guard().assess('not', [WordConfidence(0, 3, .9)])
    assert Guard().assess('do not', [WordConfidence(0, 2, .99)], confidence_source='native')['decision'] == 'RETRY_ASR'


def test_config_validation():
    config = copy.deepcopy(load_config())
    config['weights']['LOW'] = float('nan')
    with pytest.raises(ValueError):
        Detector(config)


def test_added_critical_span_counts_towards_sentence_risk():
    result = Guard().assess('please check', secondary='please check 15')
    assert result['decision'] == 'ASK_REPEAT'
    assert result['sentence_risk'] == 1.0
    assert result['secondary_spans'][-1]['risk'] == 1.0


def test_nonlexical_transcript_is_not_accepted():
    assert Guard().assess('!!!')['decision'] == 'ASK_REPEAT'
