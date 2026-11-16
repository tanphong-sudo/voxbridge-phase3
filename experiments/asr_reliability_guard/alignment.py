"""Bounded LCS: maximize protected matches first, then weighted matches."""
from .detector import canonical


def token_weights(detector, text, tokens):
    spans = detector.detect(text)
    return [max((span.importance_weight for span in spans
                 if token.start() < span.end and token.end() > span.start),
                default=detector.config['weights']['NORMAL']) for token in tokens]


def weighted_matches(left, right, left_weights, right_weights, limit, protected_threshold):
    n, m = len(left), len(right)
    if (n + 1) * (m + 1) > limit:
        raise ValueError('alignment exceeds configured cell limit')
    directions = [bytearray(m + 1) for _ in range(n + 1)]
    previous = [(0, 0.0)] * (m + 1)
    for i, first in enumerate(left, 1):
        current = [(0, 0.0)] * (m + 1)
        for j, second in enumerate(right, 1):
            protected = left_weights[i - 1] > protected_threshold or right_weights[j - 1] > protected_threshold
            match = ((previous[j - 1][0] + int(protected),
                      previous[j - 1][1] + left_weights[i - 1] + right_weights[j - 1])
                     if first == second else (-1, -1.0))
            if match >= previous[j] and match >= current[j - 1]:
                current[j] = match
            elif previous[j] >= current[j - 1]:
                current[j], directions[i][j] = previous[j], 1
            else:
                current[j], directions[i][j] = current[j - 1], 2
        previous = current
    matches = []
    i, j = n, m
    while i and j:
        direction = directions[i][j]
        if direction == 0:
            matches.append((i - 1, j - 1))
            i, j = i - 1, j - 1
        elif direction == 1:
            i -= 1
        else:
            j -= 1
    return tuple(reversed(matches))


def align_changes(detector, first, second):
    left = tuple(detector.token_pattern.finditer(first))
    right = tuple(detector.token_pattern.finditer(second))
    matches = weighted_matches([canonical(m.group()) for m in left],
                               [canonical(m.group()) for m in right],
                               token_weights(detector, first, left), token_weights(detector, second, right),
                               detector.config['max_alignment_cells'], detector.config['weights']['NORMAL'])
    changes = []
    a, b = 0, 0
    for i, j in (*matches, (len(left), len(right))):
        if a < i or b < j:
            changes.append({'operation': 'replace' if a < i and b < j else 'delete' if a < i else 'insert',
                            'first': [(m.start(), m.end()) for m in left[a:i]],
                            'second': [(m.start(), m.end()) for m in right[b:j]]})
        a, b = i + 1, j + 1
    return changes
