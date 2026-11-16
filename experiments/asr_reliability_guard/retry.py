"""Adapter boundary for an offline same-audio, same-model selective decode."""
from __future__ import annotations

import re
from dataclasses import dataclass

from .guard import Guard


@dataclass(frozen=True)
class DecodePass:
    transcript: str
    audio_sha256: str
    model_id: str
    revision: str
    config_id: str
    words: tuple = ()
    confidence_source: str | None = None

    def __post_init__(self):
        if not isinstance(self.transcript, str) or not re.fullmatch(r'[0-9a-f]{64}', self.audio_sha256):
            raise ValueError('decode pass needs transcript and SHA256 audio identity')
        if any(not isinstance(v, str) or not v.strip() for v in (self.model_id, self.revision, self.config_id)):
            raise ValueError('decode pass needs model/revision/config provenance')


def run_selective(first, decode_retry=None, *, guard=None, personalization=None, scope=None):
    guard = guard or Guard()
    if scope is not None and (scope.model_id, scope.revision) != (first.model_id, first.revision):
        raise ValueError('feedback scope must match the actual decoder model and revision')
    initial = guard.assess(first.transcript, first.words, confidence_source=first.confidence_source,
                           personalization=personalization, scope=scope)
    if initial['decision'] != 'RETRY_ASR' or decode_retry is None:
        return {**initial, 'retry_executed': False}
    try:
        second = decode_retry()
    except (RuntimeError, OSError) as error:
        return {**guard.assess(first.transcript, first.words, confidence_source=first.confidence_source,
                              retry_attempted=True, personalization=personalization, scope=scope),
                'retry_executed': True, 'retry_error': type(error).__name__}
    if not isinstance(second, DecodePass):
        raise ValueError('decoder must return DecodePass')
    if (first.audio_sha256, first.model_id, first.revision) != (second.audio_sha256, second.model_id, second.revision):
        raise ValueError('retry must use identical audio and model revision')
    if first.config_id == second.config_id:
        raise ValueError('retry needs distinct decoding configuration; replay is not evidence')
    result = guard.assess(first.transcript, first.words, confidence_source=first.confidence_source,
                          secondary=second.transcript, personalization=personalization, scope=scope,
                          secondary_words=second.words, secondary_confidence_source=second.confidence_source)
    return {**result,
            'retry_executed': True, 'first_config': first.config_id, 'second_config': second.config_id,
            'model_id': first.model_id, 'revision': first.revision,
            'audio_sha256': first.audio_sha256}
