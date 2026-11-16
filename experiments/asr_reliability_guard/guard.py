"""Maximum span risk. Agreement is categorical evidence, never native probability."""
import math
from dataclasses import asdict, dataclass

from .alignment import align_changes
from .detector import Detector
from .feedback import Scope, phrase_key


@dataclass(frozen=True)
class WordConfidence:
    start: int
    end: int
    confidence: float

    def __post_init__(self):
        if (type(self.start) is not int or type(self.end) is not int or self.start < 0 or self.end <= self.start
                or isinstance(self.confidence, bool) or not isinstance(self.confidence, (int, float))
                or not math.isfinite(self.confidence) or not 0 <= self.confidence <= 1):
            raise ValueError('invalid word confidence or offsets')


def validate_words(text, words, source):
    if words and (not isinstance(source, str) or not source.strip()):
        raise ValueError('native confidence requires explicit source provenance')
    previous = 0
    for word in words:
        if not isinstance(word, WordConfidence) or word.end > len(text) or word.start < previous:
            raise ValueError('word confidence offsets must be ordered, non-overlapping and in bounds')
        if len(text[word.start:word.end].split()) != 1:
            raise ValueError('word/token confidence cannot cover multiple words or an empty token')
        previous = word.end


def span_confidence(text, span, words):
    overlap = [word for word in words if word.start < span.end and word.end > span.start]
    # Missing any non-whitespace character means the span has no complete signal.
    if any(not text[i].isspace() and not any(w.start <= i < w.end for w in overlap)
           for i in range(span.start, span.end)):
        return None
    return min((word.confidence for word in overlap), default=None)


def changed(span, intervals):
    return any(span.start < end and span.end > start for start, end in intervals)


def checked_history(config, transcript, history, scope):
    if history is None:
        return None
    if (not isinstance(history, dict) or not isinstance(scope, Scope)
            or history.get('scope') != list(scope.values())
            or history.get('phrase_key') != phrase_key(transcript)):
        raise ValueError('history must match exact current phrase and explicit scope')
    counts = [history.get(name) for name in ('approvals', 'rejections')]
    if any(type(count) is not int or count < 0 for count in counts):
        raise ValueError('history counts must be nonnegative integers')
    approvals, rejections = counts
    weight = approvals / (approvals + config['approval_prior']) if not rejections else 0.0
    return {**history, 'approval_weight': weight,
            'reliability_bonus': config['max_personalization_bonus'] * weight}


class Guard:
    def __init__(self, config=None):
        self.detector = Detector(config)
        self.config = self.detector.config

    def assess(self, transcript, words=(), *, confidence_source=None, secondary=None,
               retry_attempted=False, personalization=None, scope=None,
               secondary_words=(), secondary_confidence_source=None):
        spans = self.detector.detect(transcript)
        validate_words(transcript, words, confidence_source)
        personalization = checked_history(self.config, transcript, personalization, scope)
        second_spans = self.detector.detect(secondary) if secondary is not None else ()
        validate_words(secondary or '', secondary_words, secondary_confidence_source)
        changes = align_changes(self.detector, transcript, secondary) if secondary is not None else []
        protected = set(self.config['protected_categories'])
        disagreements = [asdict(span) | {'side': side} for side, side_spans in
            [('first', spans), ('second', second_spans)] for span in side_spans
            if span.category in protected and any(changed(span, change[side]) for change in changes)]
        debug = [self._span_evidence(transcript, span, words, changes, secondary, personalization) for span in spans]
        reverse = [{'first': c['second'], 'second': c['first']} for c in changes]
        second_debug = [self._span_evidence(secondary, span, secondary_words, reverse, transcript, None)
                        for span in second_spans]
        all_debug = debug + second_debug
        known = [s['risk'] for s in all_debug if s['risk'] is not None]
        unknown_protected = any(s['category'] in protected and s['risk'] is None for s in all_debug)
        protected_risk = max((s['risk'] for s in all_debug if s['category'] in protected and s['risk'] is not None), default=0.0)
        # Partial known risk is a lower bound; never present it as the sentence maximum.
        risk = None if any(s['risk'] is None for s in all_debug) else max(known, default=0.0)
        decision, reason = self._decision(transcript, secondary, disagreements, unknown_protected,
                                         protected_risk, retry_attempted)
        return {'transcript': transcript, 'spans': debug, 'secondary_spans': second_debug, 'sentence_risk': risk,
                'known_risk_lower_bound': max(known, default=0.0), 'decision': decision, 'reason': reason,
                'protected_risk_lower_bound': protected_risk,
                'disagreements': disagreements, 'alignment_changes': changes,
                'native_confidence_source': confidence_source,
                'secondary_native_confidence_source': secondary_confidence_source,
                'signal': 'decode_agreement_heuristic' if secondary is not None else confidence_source,
                'personalization': dict(personalization) if personalization else None,
                'threshold_status': self.config['status']}

    def _span_evidence(self, transcript, span, words, changes, secondary, personalization):
        confidence = span_confidence(transcript, span, words)
        agreement = (not any(changed(span, change['first']) for change in changes)) if secondary is not None else None
        uncertainty = 1 - confidence if confidence is not None else None
        if agreement is not None:
            uncertainty = max(uncertainty or 0.0, float(not agreement))
        raw_risk = span.importance_weight * uncertainty if uncertainty is not None else None
        bonus = 0.0
        if personalization and raw_risk is not None and span.category in self.config['personalization_categories']:
            value = personalization.get('reliability_bonus', 0.0)
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
                raise ValueError('invalid personalization bonus')
            bonus = min(value, self.config['max_personalization_bonus'])
        return {**asdict(span), 'confidence': confidence, 'agreement': agreement,
                'uncertainty': uncertainty, 'risk_before_personalization': raw_risk,
                'personalization_bonus': bonus,
                'risk': max(0.0, raw_risk - bonus) if raw_risk is not None else None}

    def _decision(self, transcript, secondary, disagreements, unknown, risk, attempted):
        if not any(c.isalnum() for c in transcript) or secondary is not None and not any(c.isalnum() for c in secondary):
            return 'ASK_REPEAT', 'empty_transcript_or_retry'
        if disagreements:
            return 'ASK_REPEAT', 'protected_span_disagreement'
        after_retry = attempted or secondary is not None
        threshold = self.config['ask_after_retry_threshold'] if after_retry else self.config['retry_threshold']
        if unknown or risk >= threshold:
            return ('ASK_REPEAT' if after_retry else 'RETRY_ASR'), ('missing_protected_evidence' if unknown else 'span_risk')
        return 'ACCEPT', 'no_protected_risk_detected'
