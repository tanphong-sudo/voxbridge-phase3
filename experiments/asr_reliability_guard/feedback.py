"""Explicit ASR feedback only; local SQLite, exact scoped keys and event deduplication."""
import hashlib
import json
import re
import sqlite3
from dataclasses import dataclass
from pathlib import Path

from .detector import canonical, load_config, validate_config


@dataclass(frozen=True)
class Scope:
    profile: str
    site: str
    model_id: str
    revision: str
    language: str = 'en'

    def __post_init__(self):
        if any(not isinstance(v, str) or not v.strip() for v in self.values()) or self.language != 'en':
            raise ValueError('scope requires profile, site, model, revision and English language')

    def values(self):
        return (self.profile, self.site, self.model_id, self.revision, self.language)


def phrase_key(text):
    if not isinstance(text, str) or not text.strip():
        raise ValueError('feedback transcript must be nonempty')
    # Only sentence-final punctuation is ignored; decimals, hyphens, order stay intact.
    return re.sub(r'\s+', ' ', canonical(text).strip()).rstrip('.!?').strip()


class FeedbackStore:
    def __init__(self, path, config=None):
        self.config = config if config is not None else load_config()
        validate_config(self.config)
        self.path = Path(path)
        self.connection = sqlite3.connect(self.path)
        self.connection.execute('''CREATE TABLE IF NOT EXISTS events (
            event_id TEXT PRIMARY KEY, scope TEXT NOT NULL, phrase TEXT NOT NULL, kind TEXT NOT NULL)''')

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.connection.close()

    def _identity(self, scope, transcript):
        if not isinstance(scope, Scope):
            raise ValueError('explicit feedback scope required')
        phrase = phrase_key(transcript)
        if not phrase or len(transcript) > self.config['max_input_characters']:
            raise ValueError('invalid feedback transcript length')
        return json.dumps(scope.values(), ensure_ascii=False), phrase

    def record(self, scope, transcript, kind, event_id):
        identity, phrase = self._identity(scope, transcript)
        if kind not in ('confirm_asr', 'reject_asr', 'correct_asr'):
            raise ValueError('only explicit ASR feedback events may be recorded')
        if not isinstance(event_id, str) or not event_id.strip():
            raise ValueError('event_id must identify a distinct reviewed utterance')
        event = (event_id, identity, phrase, kind)
        with self.connection:
            self.connection.execute('INSERT OR IGNORE INTO events VALUES (?, ?, ?, ?)', event)
            existing = self.connection.execute('SELECT * FROM events WHERE event_id = ?', (event_id,)).fetchone()
            if existing != event:
                raise ValueError('event_id already used for a different feedback event')
        return self.evidence(scope, transcript)

    def evidence(self, scope, transcript):
        identity, phrase = self._identity(scope, transcript)
        rows = self.connection.execute('SELECT kind FROM events WHERE scope = ? AND phrase = ?', (identity, phrase)).fetchall()
        approvals = sum(kind == 'confirm_asr' for (kind,) in rows)
        rejections = sum(kind != 'confirm_asr' for (kind,) in rows)
        weight = approvals / (approvals + self.config['approval_prior']) if not rejections else 0.0
        return {'approvals': approvals, 'rejections': rejections,
                'scope': list(scope.values()), 'phrase_key': phrase,
                'approval_weight': weight, 'reliability_bonus': self.config['max_personalization_bonus'] * weight,
                'key_sha256': hashlib.sha256((identity + phrase).encode()).hexdigest(),
                'signal': 'explicit_asr_history_heuristic_not_acoustic_confidence'}
