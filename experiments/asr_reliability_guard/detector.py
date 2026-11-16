"""Deterministic non-overlapping spans; vocabulary and weights live in JSON."""
import json
import math
import re
from dataclasses import dataclass
from pathlib import Path

CONFIG_DIR = Path(__file__).parent / 'config'


def load_config(directory=CONFIG_DIR):
    directory = Path(directory)
    def read(name):
        return json.loads((directory / name).read_text(encoding='utf-8'))
    negation = read('negation_en.json')
    importance = read('importance_en.json')
    policy = read('policy.json')
    return {**importance, **policy, 'lexicons': {
        **importance['lexicons'],
        'CRITICAL_NEGATION': negation['morphological'] + negation['semantic'] + negation['patterns'],
    }}


def validate_config(config):
    weights = config['weights']
    for category in config['priority']:
        value = weights[category]
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not 0 <= value <= 1:
            raise ValueError('weights must be finite values in [0,1]')
    for key in ('retry_threshold', 'ask_after_retry_threshold', 'max_personalization_bonus'):
        value = config[key]
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not 0 < value <= 1:
            raise ValueError(f'invalid {key}')
    if type(config['approval_prior']) not in (int, float) or not math.isfinite(config['approval_prior']) or config['approval_prior'] <= 0:
        raise ValueError('approval_prior must be positive')
    for name in ('protected_categories', 'personalization_categories'):
        if set(config[name]) - set(weights):
            raise ValueError(f'unknown categories in {name}')
    protected = {category for category in weights if category.startswith(('CRITICAL_', 'HIGH_'))}
    if set(config['protected_categories']) != protected:
        raise ValueError('all HIGH/CRITICAL categories must be protected; LOW/NORMAL cannot trigger rejection')
    if any(category.startswith('CRITICAL_') for category in config['personalization_categories']):
        raise ValueError('personalization cannot discount CRITICAL risk')
    if not weights['LOW'] <= weights['NORMAL'] or any(weights[c] <= weights['NORMAL'] for c in protected):
        raise ValueError('protected categories must have higher weight than NORMAL and LOW')
    for name in ('max_input_characters', 'max_alignment_cells'):
        if type(config[name]) is not int or config[name] <= 0:
            raise ValueError(f'{name} must be a positive integer')
    for terms in config['lexicons'].values():
        if not isinstance(terms, list) or any(not isinstance(t, str) or not t.strip() for t in terms):
            raise ValueError('lexicons require nonempty strings')


def canonical(text):
    # Keep offsets intact: only replacements with the same character length.
    return text.replace('’', "'").lower()


@dataclass(frozen=True)
class Span:
    text: str
    start: int
    end: int
    category: str
    importance_weight: float


class Detector:
    def __init__(self, config=None):
        import copy
        self.config = copy.deepcopy(config if config is not None else load_config())
        validate_config(self.config)
        self.patterns = tuple((category, re.compile(pattern, re.IGNORECASE))
                              for category, pattern in self.config['patterns'].items())
        self.terms = tuple((category, re.compile(
            r'(?<!\w)' + r'\s+'.join(re.escape(canonical(part)) for part in term.split()) + r'(?!\w)', re.IGNORECASE))
            for category, terms in self.config['lexicons'].items() for term in terms)
        self.token_pattern = re.compile(self.config['token_pattern'])

    def detect(self, text):
        if not isinstance(text, str) or len(text) > self.config['max_input_characters']:
            raise ValueError('transcript must be a string within configured length limit')
        # Candidates use configured category priority, then longest match at each start.
        candidates = [(m.start(), m.end(), category)
                      for category, pattern in self.patterns + self.terms
                      for m in pattern.finditer(text.replace('’', "'"))]
        priorities = {category: i for i, category in enumerate(self.config['priority'])}
        chosen = []
        for start, end, category in sorted(candidates, key=lambda x: (priorities[x[2]], x[0], -(x[1] - x[0]))):
            if not any(start < old_end and end > old_start for old_start, old_end, _ in chosen):
                chosen = [*chosen, (start, end, category)]
        defaults = [(m.start(), m.end(), 'NORMAL') for m in self.token_pattern.finditer(text)
                    if not any(m.start() < end and m.end() > start for start, end, _ in chosen)]
        return tuple(Span(text[start:end], start, end, category, self.config['weights'][category])
                     for start, end, category in sorted(chosen + defaults))
