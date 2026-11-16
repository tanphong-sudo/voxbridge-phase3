#!/usr/bin/env python3
"""Aggregate declared primary-turn human ratings; never run or certify the speech pipeline."""
from __future__ import annotations

import argparse
from collections import defaultdict
import csv
import hashlib
import json
from pathlib import Path
import re
from statistics import mean, median


PROVENANCE = {'btc_human_label', 'btc_ai_label', 'team_bilingual_review', 'unreviewed'}
TURN_COLUMNS = {'run_id', 'turn_id', 'sample_id', 'source_lang', 'target_lang', 'attempt_kind',
                'final_translation', 'output_audio_available', 'actual_audio_emitted', 'output_audio_path'}
RATING_COLUMNS = {'run_id', 'turn_id', 'rater_id', 'rating_stage', 'reference_provenance',
                  'meaning_0_to_4', 'clarity_1_to_5', 'naturalness_1_to_5', 'critical_error',
                  'output_available', 'adjudication_note'}


def text(value, label):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f'{label}: nonempty string required')
    return value


def boolean(value, label):
    if type(value) is bool:
        return value
    if isinstance(value, str) and value in ('true', 'false'):
        return value == 'true'
    raise ValueError(f'{label}: boolean true/false required')


def integer(value, low, high, label, optional=False):
    if optional and (value is None or value == ''):
        return None
    if type(value) is int:
        number = value
    elif isinstance(value, str) and re.fullmatch(r'[0-9]+', value):
        number = int(value)
    else:
        raise ValueError(f'{label}: integer required')
    if not low <= number <= high:
        raise ValueError(f'{label}: expected {low}..{high}')
    return number


def aggregate(manifest, turns, ratings, run_id):
    text(run_id, 'run_id')
    if not isinstance(manifest, list) or not manifest:
        raise ValueError('nonempty manifest required')
    samples = {}
    for row in manifest:
        if not isinstance(row, dict): raise ValueError('manifest row must be an object')
        sid = text(row.get('id'), 'sample id')
        if sid in samples: raise ValueError(f'duplicate sample: {sid}')
        direction = (row.get('source_lang'), row.get('target_lang'))
        if direction not in [('vi', 'en'), ('en', 'vi')]: raise ValueError(f'{sid}: invalid direction')
        text(row.get('condition'), f'{sid}: condition')
        if type(row.get('eligible_general')) is not bool: raise ValueError(f'{sid}: eligibility must be boolean')
        provenance = text(row.get('reference_provenance'), f'{sid}: provenance')
        if provenance not in PROVENANCE: raise ValueError(f'{sid}: unknown provenance')
        samples[sid] = dict(row)

    if not isinstance(turns, list) or not isinstance(ratings, list):
        raise ValueError('turns and ratings must be lists')
    by_sample, by_turn = {}, {}
    for row in turns:
        if not isinstance(row, dict): raise ValueError('turn must be an object')
        if row.get('run_id') != run_id: raise ValueError('foreign run in turns')
        tid, sid = text(row.get('turn_id'), 'turn id'), text(row.get('sample_id'), 'sample id')
        if tid in by_turn or sid in by_sample: raise ValueError('duplicate turn/sample primary attempt')
        if sid not in samples: raise ValueError(f'unknown sample: {sid}')
        if row.get('attempt_kind') != 'primary': raise ValueError('only primary attempts may be scored')
        sample = samples[sid]
        if any(row.get(k) != sample[k] for k in ('source_lang', 'target_lang')):
            raise ValueError(f'{tid}: direction mismatch')
        if not isinstance(row.get('final_translation'), str): raise ValueError(f'{tid}: text must be string')
        audio = boolean(row.get('output_audio_available'), f'{tid}: audio availability')
        emitted = boolean(row.get('actual_audio_emitted'), f'{tid}: audio emitted')
        if audio: text(row.get('output_audio_path'), f'{tid}: audio path')
        by_turn[tid] = dict(row, audio=audio, emitted=emitted, has_text=bool(row['final_translation'].strip()))
        by_sample[sid] = by_turn[tid]

    reviews = defaultdict(list)
    identities = set()
    for row in ratings:
        if not isinstance(row, dict): raise ValueError('rating must be an object')
        if row.get('run_id') != run_id: raise ValueError('foreign run in ratings')
        tid = text(row.get('turn_id'), 'rating turn id')
        if tid not in by_turn: raise ValueError(f'unknown rating turn: {tid}')
        rater = text(row.get('rater_id'), f'{tid}: rater')
        stage = text(row.get('rating_stage'), f'{tid}: stage')
        if stage not in ('independent', 'adjudicated'): raise ValueError(f'{tid}: invalid stage')
        identity = tid, rater, stage
        if identity in identities: raise ValueError(f'{tid}: duplicate rater/stage')
        identities.add(identity)
        turn = by_turn[tid]
        if row.get('reference_provenance') != samples[turn['sample_id']]['reference_provenance']:
            raise ValueError(f'{tid}: provenance mismatch')
        available = boolean(row.get('output_available'), f'{tid}: rated output availability')
        if available != turn['has_text']: raise ValueError(f'{tid}: rated text availability mismatch')
        meaning = integer(row.get('meaning_0_to_4'), 0, 4, f'{tid}: meaning')
        clarity = integer(row.get('clarity_1_to_5'), 1, 5, f'{tid}: clarity', optional=True)
        naturalness = integer(row.get('naturalness_1_to_5'), 1, 5, f'{tid}: naturalness', optional=True)
        critical = boolean(row.get('critical_error'), f'{tid}: critical error')
        if not available and (meaning != 0 or clarity is not None or naturalness is not None):
            raise ValueError(f'{tid}: no translation cannot receive positive meaning/audio ratings')
        if not turn['audio'] and (clarity is not None or naturalness is not None):
            raise ValueError(f'{tid}: audio ratings need available audio')
        if stage == 'adjudicated': text(row.get('adjudication_note'), f'{tid}: adjudication note')
        reviews[tid].append(dict(stage=stage, rater=rater, meaning=meaning, critical=critical,
                                 clarity=clarity, naturalness=naturalness))

    resolved = {}
    unresolved = set()
    for tid, entries in reviews.items():
        independent = [r for r in entries if r['stage'] == 'independent']
        final = [r for r in entries if r['stage'] == 'adjudicated']
        if len(final) > 1 or (final and not independent):
            raise ValueError(f'{tid}: adjudication requires independent review and a single final record')
        if not final and len({(r['meaning'], r['critical']) for r in independent}) > 1:
            unresolved.add(tid)
            continue
        selected = final or independent
        resolved[tid] = dict(meaning=selected[0]['meaning'], critical=selected[0]['critical'],
                             clarity=_average([r['clarity'] for r in selected if r['clarity'] is not None]),
                             naturalness=_average([r['naturalness'] for r in selected if r['naturalness'] is not None]))

    def summarize(ids):
        present = [by_sample[s] for s in ids if s in by_sample]
        eligible = [s for s in ids if samples[s]['eligible_general']]
        known_success = unknown = 0
        for sid in eligible:
            turn = by_sample.get(sid)
            if turn is None:
                unknown += 1
            elif turn['has_text']:
                rating = resolved.get(turn['turn_id'])
                if rating is None: unknown += 1
                elif rating['meaning'] >= 3 and not rating['critical']: known_success += 1
        n = len(eligible)
        rated = [resolved[t['turn_id']] for t in present if t['has_text'] and t['turn_id'] in resolved]
        result = dict(expected=len(ids), present=len(present), missing_turns=len(ids)-len(present),
                      eligible_general=n, text_outputs=sum(t['has_text'] for t in present),
                      audio_available=sum(t['audio'] for t in present), actual_emitted=sum(t['emitted'] for t in present),
                      rated_text_outputs=len(rated),
                      unrated_text_outputs=sum(t['has_text'] and t['turn_id'] not in reviews for t in present),
                      unresolved_ratings=sum(t['turn_id'] in unresolved for t in present),
                      observed_critical_errors=sum(r['critical'] for t in present if (r := resolved.get(t['turn_id']))),
                      critical_flagged_turns=sum(any(r['critical'] for r in reviews.get(t['turn_id'], [])) for t in present),
                      double_reviewed_turns=sum(sum(r['stage']=='independent' for r in reviews.get(t['turn_id'], [])) >= 2 for t in present),
                      known_semantic_successes=known_success, semantic_unknown=unknown,
                      semantic_success_rate=known_success/n if n and not unknown else None,
                      semantic_success_lower_bound=known_success/n if n else None,
                      semantic_success_upper_bound=(known_success+unknown)/n if n else None,
                      rated_text_mean_meaning=_average([r['meaning'] for r in rated]))
        for key in ('clarity', 'naturalness'):
            values = [r[key] for r in rated if r[key] is not None]
            result[key+'_median'] = median(values) if values else None
            result[key+'_rated_turns'] = len(values)
            result[key+'_unrated_audio_outputs'] = sum(t['audio'] for t in present)-len(values)
        return result

    overall = summarize(list(samples))
    report = dict(run_id=run_id, scope='aggregation_only', runtime_verified=False,
                  quality_thresholds_evaluated=False, overall=overall,
                  data_complete=not any(overall[k] for k in ('missing_turns', 'unrated_text_outputs', 'unresolved_ratings',
                                                            'clarity_unrated_audio_outputs', 'naturalness_unrated_audio_outputs')),
                  observed_critical_errors=overall['observed_critical_errors'],
                  note='Declared data only. Semantic rate concerns text; audio availability/emission are separate. No official quality threshold or runtime certification.')
    for label, key in [('direction', None), ('condition', 'condition'), ('reference_provenance', 'reference_provenance')]:
        groups = defaultdict(list)
        for sid, sample in samples.items():
            group = sample[key] if key else sample['source_lang']+'-'+sample['target_lang']
            groups[group].append(sid)
        report['by_'+label] = {group:summarize(ids) for group,ids in sorted(groups.items())}
    joint = defaultdict(lambda: defaultdict(list))
    for sid, sample in samples.items():
        joint[sample['source_lang']+'-'+sample['target_lang']][sample['condition']].append(sid)
    report['by_direction_condition'] = {direction:{condition:summarize(ids) for condition,ids in sorted(groups.items())}
                                        for direction,groups in sorted(joint.items())}
    return report


def _average(values):
    return mean(values) if values else None


def _object(pairs):
    result = {}
    for key, value in pairs:
        if key in result: raise ValueError(f'duplicate JSON key: {key}')
        result[key] = value
    return result


def _csv(path, required):
    with path.open(encoding='utf-8-sig', newline='') as handle:
        reader = csv.DictReader(handle)
        fields = reader.fieldnames or []
        if not fields or len(set(fields)) != len(fields) or not required <= set(fields):
            raise ValueError(f'{path}: missing/duplicate CSV headers')
        rows = list(reader)
        if any(None in row or any(v is None for v in row.values()) for row in rows):
            raise ValueError(f'{path}: malformed CSV column count')
        return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('manifest', 'turns', 'ratings'): parser.add_argument('--'+name, type=Path, required=True)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args(argv)
    try:
        if args.output:
            for source in (args.manifest, args.turns, args.ratings):
                if args.output.resolve() == source.resolve() or (args.output.exists() and args.output.samefile(source)):
                    raise ValueError('output must not overwrite an input or its alias')
        manifest = [json.loads(line, object_pairs_hook=_object) for line in args.manifest.read_text().splitlines() if line.strip()]
        report = aggregate(manifest, _csv(args.turns, TURN_COLUMNS), _csv(args.ratings, RATING_COLUMNS), args.run_id)
        report['input_sha256'] = {str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in (args.manifest, args.turns, args.ratings)}
        payload = json.dumps(report, ensure_ascii=False, indent=2)+'\n'
        if args.output: args.output.write_text(payload, encoding='utf-8')
    except (ValueError, OSError) as error:
        parser.error(str(error))
    print(payload, end='')
    return 0 if report['data_complete'] and not report['observed_critical_errors'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
