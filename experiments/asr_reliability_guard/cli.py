"""Offline JSON debugging and explicit transcript feedback; no app integration."""
import argparse
import json
import sqlite3
from pathlib import Path

from .feedback import FeedbackStore, Scope
from .guard import Guard, WordConfidence
from .retry import DecodePass, run_selective


def read_object(path):
    value = json.loads(Path(path).read_text(encoding='utf-8'))
    if not isinstance(value, dict):
        raise ValueError('JSON input must be an object')
    return value


def native_words(value):
    words = value.get('words', [])
    if not isinstance(words, list):
        raise ValueError('words must be an array of offset/confidence objects')
    return tuple(WordConfidence(**word) for word in words)


def decode_pass(path):
    data = read_object(path)
    return DecodePass(**{**data, 'words': native_words(data)})


def scope_args(parser):
    for name in ('profile', 'site', 'model', 'revision'):
        parser.add_argument('--' + name)
    parser.add_argument('--db', type=Path)


def feedback_evidence(args, config):
    if not args.db:
        return None
    scope = Scope(args.profile, args.site, args.model, args.revision)
    with FeedbackStore(args.db, config) as store:
        if args.command == 'feedback':
            return store.record(scope, args.transcript, args.kind, args.event_id)
        return store.evidence(scope, args.transcript)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    assess = commands.add_parser('assess')
    assess.add_argument('transcript')
    assess.add_argument('--confidence-json', type=Path)
    scope_args(assess)
    feedback = commands.add_parser('feedback')
    feedback.add_argument('transcript')
    feedback.add_argument('--kind', choices=('confirm_asr', 'reject_asr', 'correct_asr'), required=True)
    feedback.add_argument('--event-id', required=True)
    scope_args(feedback)
    retry = commands.add_parser('retry', help='replay two real saved same-model decoding passes')
    retry.add_argument('--first', required=True, type=Path)
    retry.add_argument('--second', type=Path)
    args = parser.parse_args(argv)
    try:
        guard = Guard()
        result = execute(args, guard)
    except (ValueError, OSError, TypeError, KeyError, sqlite3.Error) as error:
        parser.error(str(error))
    print(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False))
    return 0


def execute(args, guard):
    if args.command == 'retry':
        first = decode_pass(args.first)
        return run_selective(first, (lambda: decode_pass(args.second)) if args.second else None, guard=guard)
    if args.command == 'feedback' and not args.db:
        raise ValueError('feedback needs --db and explicit --profile/--site/--model/--revision')
    evidence = feedback_evidence(args, guard.config)
    if args.command == 'feedback':
        return evidence
    native = read_object(args.confidence_json) if args.confidence_json else {}
    scope = Scope(args.profile, args.site, args.model, args.revision) if args.db else None
    return guard.assess(args.transcript, native_words(native), confidence_source=native.get('source'),
                        personalization=evidence, scope=scope)


if __name__ == '__main__':
    raise SystemExit(main())
