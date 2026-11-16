"""Strict, offline BTC replay. References are available only to posthoc diagnostics."""
from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from functools import lru_cache
from pathlib import Path

from . import scoring
from .detector import Detector
from .guard import Guard, align_changes, changed

ROOT = Path(__file__).resolve().parents[2]
EXPERIMENT = Path(__file__).resolve().parent
EVIDENCE = ROOT / 'output/phase3/en-asr-20261113/evidence'
DECISIONS = ('ACCEPT', 'RETRY_ASR', 'ASK_REPEAT')
RUN_FILES = {
    'app': 'parent/baseline-app.jsonl',
    'small_hf': 'whisper/whisper-small-bf16-en100.jsonl',
    'medium': 'whisper/whisper-medium-bf16-en100.jsonl',
    'turbo': 'whisper/whisper-large-v3-turbo-bf16-en100.jsonl',
    'parakeet': 'parakeet/full100.jsonl',
}
RUNS = {name: EVIDENCE / relative for name, relative in RUN_FILES.items()}


@lru_cache(maxsize=1)
def load_scorer():
    """Use the tracked exact subset, independent of optional ignored BTC artifacts."""
    return scoring


def require_evidence(directory):
    """Check optional local inputs before evaluation creates any outputs."""
    paths = {name: directory / relative for name, relative in RUN_FILES.items()}
    required = [directory / 'inputs/en100.jsonl', directory / 'parent/comparison.json', *paths.values()]
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise ValueError('BTC evidence not available; restore the frozen local evidence directory '
                         'or pass --evidence-dir. Missing: ' + ', '.join(missing))
    return paths


def display_path(path):
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def protected_differences(reference, hypothesis):
    """Oracle inspection only; never feeds the live guard or changes a decision."""
    detector = Detector()
    changes = align_changes(detector, reference, hypothesis)
    return [{'side': side, 'text': span.text, 'category': span.category}
            for side, text in [('first', reference), ('second', hypothesis)]
            for span in detector.detect(text)
            if span.category in detector.config['protected_categories']
            and any(changed(span, change[side]) for change in changes)]


def evaluate_run(manifest, rows, *, guard=None, frozen=None):
    scorer = load_scorer()
    sources, results = scorer.validate_pair(manifest, rows)
    guard = guard or Guard()
    normalized = {row['id']: row for row in frozen['per_sample']} if frozen else {}
    if frozen and (len(normalized) != len(frozen['per_sample']) or set(normalized) != set(sources)):
        raise ValueError('frozen normalized evidence requires identical sample coverage')
    per_sample = []
    for identifier, source in sources.items():
        row = results[identifier]
        hypothesis, reference = row['hypothesis'], source['reference_source']
        # This is the entire live interface: no reference, oracle slots or reference WER.
        debug = guard.assess(hypothesis)
        raw_ops = scorer.edit_operations(scorer.raw_words(reference), scorer.raw_words(hypothesis))
        cached = normalized.get(identifier)
        if cached and (cached['reference'] != reference or cached['hypothesis'] != hypothesis):
            raise ValueError(f'frozen normalized evidence differs: {identifier}')
        per_sample.append({
            'id': identifier, 'reference': reference, 'hypothesis': hypothesis, 'guard': debug,
            'raw_operations': raw_ops,
            'normalized_operations': cached['normalized_operations'] if cached else None,
            'skipped': row.get('skipped'), 'error': row.get('error'),
            'posthoc_protected_differences': protected_differences(reference, hypothesis),
        })
    return summarize(per_sample, frozen)


def summarize(samples, frozen):
    counts = Counter(row['guard']['decision'] for row in samples)
    trigger_categories, trigger_spans, trigger_samples = Counter(), Counter(), Counter()
    detector = Detector()
    for row in samples:
        if row['guard']['decision'] == 'RETRY_ASR':
            triggers = [span for span in row['guard']['spans']
                        if span['category'] in detector.config['protected_categories']]
            trigger_categories.update(span['category'] for span in triggers)
            trigger_spans.update((span['text'].lower(), span['category']) for span in triggers)
            trigger_samples.update(sorted({(span['text'].lower(), span['category']) for span in triggers}))
    edits = sum(row['raw_operations']['edits'] for row in samples)
    units = sum(row['raw_operations']['reference_units'] for row in samples)
    wrong_raw = [row for row in samples if row['raw_operations']['edits'] and row['guard']['decision'] == 'ACCEPT']
    wrong_norm = [row for row in samples if row['normalized_operations']
                  and row['normalized_operations']['edits'] and row['guard']['decision'] == 'ACCEPT']
    correct_refusals = [row for row in samples if row['guard']['decision'] != 'ACCEPT'
                        and (row['normalized_operations'] or row['raw_operations'])['edits'] == 0]
    return {
        'samples': len(samples), 'decisions': {key: counts[key] for key in DECISIONS},
        'skipped': sum(bool(row['skipped']) for row in samples),
        'runtime_errors': sum(bool(row['error']) for row in samples),
        'raw_wer': edits / units if units else None, 'raw_edits': edits, 'raw_reference_units': units,
        'normalized_wer': frozen['wer_norm'] if frozen else None,
        'normalized_metric_source': 'SHA256-verified frozen scorer report; not recomputed' if frozen else 'unavailable; pinned normalizer not invoked',
        'trigger_categories': dict(trigger_categories.most_common()),
        'trigger_spans': [{'text': text, 'category': category, 'count': trigger_spans[(text, category)],
                           'sample_count': count}
                          for (text, category), count in trigger_samples.most_common(30)],
        'wrong_but_accepted_raw': wrong_raw,
        'wrong_but_accepted_normalized': wrong_norm,
        'correct_but_not_accepted': correct_refusals,
        'posthoc_protected_difference_samples': sum(bool(row['posthoc_protected_differences']) for row in samples),
        'per_sample': samples,
    }


def audio_hash(row):
    return row.get('audio_sha256', row.get('wav_sha256'))


def cross_model_diagnostic(manifest, first, second):
    scorer = load_scorer()
    sources, left = scorer.validate_pair(manifest, first)
    _, right = scorer.validate_pair(manifest, second)
    guard = Guard()
    samples = []
    for identifier in sources:
        a, b = left[identifier], right[identifier]
        # Shared VAD skip has no decoded audio hash: retain it, with an explicit skip label.
        both_skipped = bool(a.get('skipped')) and bool(b.get('skipped'))
        if not both_skipped and (not audio_hash(a) or audio_hash(a) != audio_hash(b)):
            raise ValueError(f'cross-model diagnostic requires matching actual audio hashes: {identifier}')
        debug = guard.assess(a['hypothesis'], secondary=b['hypothesis'])
        samples.append({'id': identifier, 'first_hypothesis': a['hypothesis'],
                        'second_hypothesis': b['hypothesis'], 'shared_vad_skip': both_skipped,
                        'audio_sha256': audio_hash(a), 'guard': debug})
    counts = Counter(row['guard']['decision'] for row in samples)
    return {'evidence_type': 'cross_model_diagnostic_not_same_model_retry',
            'samples': len(samples), 'decisions': {key: counts[key] for key in DECISIONS},
            'per_sample': samples}


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def verified_frozen(manifest_path, result_path, name, *, evidence_dir=None):
    path = (evidence_dir if evidence_dir is not None else EVIDENCE) / 'parent/comparison.json'
    report = json.loads(path.read_text(encoding='utf-8'))
    frozen = report['runs'][name]
    if frozen['normalizer_version'] != load_scorer().NORMALIZER_VERSION:
        raise ValueError('frozen normalizer version differs from pinned scorer')
    if sha256(manifest_path) != report['manifest_sha256'] or sha256(result_path) != frozen['result_file_sha256']:
        raise ValueError(f'input SHA256 differs from frozen score report: {name}')
    return frozen


def write_outputs(directory, runs, diagnostics, inputs):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    def write_rows(name, rows):
        (directory / f'evaluation-{name}.jsonl').write_text(
            ''.join(json.dumps(row, ensure_ascii=False, allow_nan=False) + '\n' for row in rows), encoding='utf-8')
    for name, run in {**runs, **diagnostics}.items():
        write_rows(name, run['per_sample'])
    def compact(run):
        value = {key: item for key, item in run.items() if key != 'per_sample'}
        for key in ('wrong_but_accepted_raw', 'wrong_but_accepted_normalized', 'correct_but_not_accepted'):
            if key in value:
                value[key] = [{**{k: v for k, v in row.items() if k != 'guard'},
                               'decision': row['guard']['decision'], 'reason': row['guard']['reason']}
                              for row in value[key]]
        return value
    summary = {'scope': 'Offline frozen-transcript evaluation; no model inference or production changes',
               'native_word_confidence': None, 'same_model_second_decode_available': False,
               'inputs': inputs,
               'runs': {name: compact(run) for name, run in runs.items()},
               'diagnostics': {name: compact(run) for name, run in diagnostics.items()}}
    (directory / 'evaluation-summary.json').write_text(
        json.dumps(summary, ensure_ascii=False, indent=2, allow_nan=False) + '\n', encoding='utf-8')


def excerpt(text, limit=220):
    text = ' '.join(text.split()).replace('|', '\\|').replace('`', "'")
    return text if len(text) <= limit else text[:limit] + '…'


def markdown_report(runs, diagnostics, inputs):
    lines = [
        '# BTC English 100 — Risk-Aware ASR Reliability Guard', '',
        'Replay output đã lưu, 100 câu/run, gồm 99 inference và một VAD skip EV-2748. '
        'Không chạy ASR mới, không sửa transcript, không đổi WER. Reference chỉ dùng để chấm và inspect sau quyết định guard.', '',
        'Parakeet trong bảng này là v3 Transformers của lượt EN100 đã đóng băng. '
        'Các lần nghe/tăng âm lượng v2 NeMo là thí nghiệm riêng, không gộp với số liệu dưới đây.', '',
        '## Kết quả single-pass thực tế', '',
        'Không có word/token confidence trong logs. Giữ `confidence=null`; span HIGH/CRITICAL thiếu evidence → RETRY_ASR. '
        'Đây là yêu cầu chạy lại còn chờ, không phải đã chạy retry. Câu rỗng/VAD skip → ASK_REPEAT trong API guard; '
        'app production vẫn giữ logic NO_SPEECH hiện tại.', '',
        '| Run | N | ACCEPT | RETRY_ASR | ASK_REPEAT | WER raw | WER normalized (frozen) | ASR sai vẫn ACCEPT (normalized) | Đúng nhưng chưa ACCEPT |',
        '|---|---:|---:|---:|---:|---:|---:|---:|---:|',
    ]
    for name, run in runs.items():
        c = run['decisions']
        lines.append(f"| {name} | {run['samples']} | {c['ACCEPT']} | {c['RETRY_ASR']} | {c['ASK_REPEAT']} | "
                     f"{run['raw_wer']:.2%} | {run['normalized_wer']:.2%} | {len(run['wrong_but_accepted_normalized'])} | {len(run['correct_but_not_accepted'])} |")
    lines += ['', 'WER raw được tính bằng scorer cũ. Normalized WER/per-sample operations được đọc lại từ '
              '`evidence/parent/comparison.json` sau khi xác minh SHA256 manifest/result và exact reference/hypothesis; '
              'không cài dependency, không chạy normalizer khác. `whisper-normalizer==1.0.1` hiện không có trong Python của máy này. '
              'Raw WER và normalized WER đều phải được đọc: Parakeet EV-0358 lặp “two” 666 lần có thể bị normalization che mất.', '',
              '## Span kích hoạt và false alarm', '']
    for name, run in runs.items():
        spans = ', '.join(f"`{excerpt(s['text'], 45)}` ({s['category']}, {s['sample_count']} câu/{s['count']} spans)"
                          for s in run['trigger_spans'][:12])
        lines += [f"**{name}:** {spans or 'Không có span protected.'}", '']
        examples = run['correct_but_not_accepted'][:3]
        for row in examples:
            lines.append(f"- False alarm/pending rõ theo oracle: {row['id']} → {row['guard']['decision']}, "
                         f"hypothesis `{excerpt(row['hypothesis'])}`. Transcript đúng theo scorer nhưng thiếu confidence span quan trọng.")
        lines.append('')
    lines += ['Đây là false alarm theo correctness oracle, chưa chứng minh ngưỡng tệ: log thiếu confidence khiến policy bảo thủ. '
              'BTC là hội thoại chung, không phải corpus an toàn công nghiệp; từ như “start”, “set”, “open” dễ kích hoạt ngoài ngữ cảnh vận hành. '
              'Không dùng các reference này để điều chỉnh confidence hay sửa output.', '',
              '## ASR sai nhưng guard vẫn ACCEPT — cần đọc', '']
    for name, run in runs.items():
        lines += [f"### {name}: {len(run['wrong_but_accepted_normalized'])} câu có lỗi normalized", '',
                  '| ID | Reference | Hypothesis | Protected difference (posthoc) |', '|---|---|---|---|']
        for row in run['wrong_but_accepted_normalized']:
            protected = ', '.join(f"{s['side']}:{s['text']}" for s in row['posthoc_protected_differences']) or 'none detected'
            lines.append(f"| {row['id']} | {excerpt(row['reference'])} | {excerpt(row['hypothesis'])} | {excerpt(protected)} |")
        lines.append('')
    lines += ['`ACCEPT` chỉ có nghĩa không phát hiện protected risk trong hypothesis hiện tại. '
              'Nó không xác nhận transcript đúng. Negation bị ASR bỏ hoàn toàn có thể biến thành NORMAL words và lọt guard; '
              'hai pass cùng bỏ negation cũng không thể được phát hiện bằng agreement. Chi tiết raw-only lỗi nằm trong JSON summary.', '',
              '## Cross-model diagnostic riêng', '',
              'So Whisper Small và Parakeet trên cùng WAV hash; đây **không phải** retry cùng model với hai decoding configuration. '
              'Không đưa số này vào bảng single-pass và không coi là confidence xác suất.', '',
              '| Diagnostic | N | ACCEPT | RETRY_ASR | ASK_REPEAT |', '|---|---:|---:|---:|---:|']
    for name, run in diagnostics.items():
        c = run['decisions']
        lines.append(f"| {name} | {run['samples']} | {c['ACCEPT']} | {c['RETRY_ASR']} | {c['ASK_REPEAT']} |")
    lines += ['', 'Khác format số (“one”/“1”), contraction, hoặc tên riêng có thể tạo disagreement bảo thủ. '
              'Các token LOW khác nhau không tự tạo ASK_REPEAT. Một shared VAD skip được giữ và đánh dấu riêng.', '',
              '## Tái lập và giới hạn', '',
              'Từ repo root: `python3 -m experiments.asr_reliability_guard.evaluate`.', '',
              '- `results/evaluation-*.jsonl`: toàn bộ 100 debug decisions/run và cross-model alignment.',
              '- `results/evaluation-summary.json`: counts, danh sách lỗi lọt ACCEPT, false alarm, input SHA256.',
              '- Không học từ BTC reference. Không có user confirmation thật trong dataset nên evaluation dùng personalization bằng 0.',
              '- Không có audio/model local tương ứng để rerun same-model greedy/beam; đây là offline replay trên evidence đã có.',
              '- Threshold/weight là prototype chưa calibration. Chưa đo selective retry latency, false reject trên acoustic holdout hoặc unsafe permission.',
              '- Bước tiếp: thu word evidence/provenance hoặc paired decode cùng audio; split calibration/holdout theo speaker/site; '
              'review negation/number/ID mistakes và tối ưu false alarm cùng lỗi protected lọt ACCEPT.', '',
              '### Input SHA256', '', '| File | SHA256 |', '|---|---|']
    lines.extend(f"| {entry['path']} | `{entry['sha256']}` |" for entry in inputs)
    return '\n'.join(lines) + '\n'


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=EXPERIMENT / 'results')
    parser.add_argument('--evidence-dir', type=Path, default=EVIDENCE,
                        help='Local frozen BTC artifacts; no download is performed')
    args = parser.parse_args(argv)
    try:
        run_paths = require_evidence(args.evidence_dir)
    except ValueError as error:
        parser.error(str(error))
    scorer = load_scorer()
    manifest_path = args.evidence_dir / 'inputs/en100.jsonl'
    manifest = scorer.read_jsonl(manifest_path)
    if len(manifest) != 100:
        raise ValueError('BTC evaluation requires exactly 100 frozen manifest samples')
    inputs = [{'path': display_path(path), 'sha256': sha256(path)}
              for path in [manifest_path, args.evidence_dir / 'parent/comparison.json',
                           Path(scoring.__file__), Path(scoring.__file__).with_name('scoring_provenance.json'),
                           *run_paths.values(),
                           *sorted((EXPERIMENT / 'config').glob('*.json'))]]
    rows = {name: scorer.read_jsonl(path) for name, path in run_paths.items()}
    runs = {name: evaluate_run(manifest, rows[name], frozen=verified_frozen(
                manifest_path, path, name, evidence_dir=args.evidence_dir))
            for name, path in run_paths.items()}
    diagnostics = {'small_vs_parakeet': cross_model_diagnostic(manifest, rows['small_hf'], rows['parakeet'])}
    write_outputs(args.output, runs, diagnostics, inputs)
    (EXPERIMENT / 'BTC_RESULTS.md').write_text(markdown_report(runs, diagnostics, inputs), encoding='utf-8')
    print(json.dumps({name: run['decisions'] for name, run in runs.items()}, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
