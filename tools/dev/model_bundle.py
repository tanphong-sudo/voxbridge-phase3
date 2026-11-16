"""Read-only cache validation and allowlisted Phase-2 VI/EN APK staging. Never exports models."""
import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
import shutil
import tempfile
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
from model_integrity import integrity_text, payload_file

PROFILE = 'SMOKE_VI_EN'
ASR_DIRS = ('speech_to_text/phowhisper-base-onnx', 'speech_to_text/whisper-small-onnx')
ASR_MODELS = {
    'speech_to_text/phowhisper-base-onnx': 'vinai/PhoWhisper-base',
    'speech_to_text/whisper-small-onnx': 'openai/whisper-small',
}
MT_DIRS = ('translation/opus-mt-vi-en', 'translation/opus-mt-en-vi')
VOICE_DIRS = tuple('text_to_speech/piper/' + v for v in (
    'vits-piper-vi_VN-vais1000-medium', 'vits-piper-en_US-lessac-medium'))
VI_VOICE_DIR = 'text_to_speech/piper/vits-piper-vi_VN-vais1000-medium'
GRAPHS = ('encoder_model', 'decoder_model', 'decoder_with_past_model')
PHRASES = ('ask_repeat_vi', 'ask_repeat_en', 'no_speech_vi', 'no_speech_en')
COMMAND_PHRASES = tuple(action + '_' + lang for action in
                        ('stop_conveyor', 'stop_machine', 'do_not_enter', 'evacuate')
                        for lang in ('vi', 'en'))


def available_phrases(root):
    cache = root/'text_to_speech/cache'
    return PHRASES + tuple(pid for pid in COMMAND_PHRASES
                           if all((cache/(pid + '.' + ext)).is_file() and
                                  (cache/(pid + '.' + ext)).stat().st_size > 0
                                  for ext in ('pcm', 'sr')))


METADATA_NAMES = {'source.json', 'io.json', 'config.json', 'generation_config.json',
                  'tokenizer_config.json', 'special_tokens_map.json', 'preprocessor_config.json'}
ASR_NAMES = {s + '.onnx' for s in GRAPHS} | {'whisper_tokens.json', 'mel_80_201.f32.bin',
            'source.json', 'shared_weights.bin', 'vocab.json', 'tokenizer.json', 'merges.txt', 'added_tokens.json'}
MT_NAMES = {s + '_quantized.onnx' for s in GRAPHS} | {'source.spm', 'target.spm', 'vocab.json'}


def required_files(include_asr=True):
    yield 'vad/silero_vad.onnx'
    for directory in ASR_DIRS if include_asr else ():
        for file in (*[s + '.onnx' for s in GRAPHS], 'whisper_tokens.json', 'mel_80_201.f32.bin'):
            yield f'{directory}/{file}'
    for directory in MT_DIRS:
        for file in (*[s + '_quantized.onnx' for s in GRAPHS], 'source.spm', 'target.spm', 'vocab.json'):
            yield f'{directory}/{file}'
    for directory in VOICE_DIRS:
        yield f'{directory}/tokens.txt'
    yield f'{VI_VOICE_DIR}/MODEL_CARD'
    yield f'{VI_VOICE_DIR}/source.json'
    for phrase in PHRASES:
        for extension in ('pcm', 'sr'):
            yield f'text_to_speech/cache/{phrase}.{extension}'


def validate(root, include_asr=True):
    missing = [name for name in required_files(include_asr) if not (root/name).is_file() or (root/name).stat().st_size == 0]
    for directory in ASR_DIRS if include_asr else ():
        provenance = root/directory/'source.json'
        if not provenance.is_file():
            missing.append(f'{directory}/source.json')
        else:
            try:
                metadata = json.loads(provenance.read_text())
                if not isinstance(metadata, dict):
                    raise ValueError('source.json root must be an object')
            except (OSError, ValueError):
                missing.append(f'{directory}/source.json (invalid JSON)')
                metadata = None
            if metadata is None:
                pass
            elif metadata.get('model') != ASR_MODELS[directory]:
                missing.append(
                    f'{directory}/source.json model={metadata.get("model")!r}; '
                    f'expected {ASR_MODELS[directory]!r}')
            elif metadata.get('precision') != 'float32':
                missing.append(f'{directory}/source.json precision=float32')
            elif not isinstance(metadata.get('revision'), str) or not metadata['revision'].strip():
                missing.append(f'{directory}/source.json revision')
            if metadata is not None and metadata.get('storage') == 'lossless shared ONNX external initializers':
                weights = root/directory/'shared_weights.bin'
                if not weights.is_file() or weights.stat().st_size == 0:
                    missing.append(f'{directory}/shared_weights.bin (nonempty required)')
        if not any((root/directory/name).is_file() for name in ('vocab.json', 'tokenizer.json')):
            missing.append(f'{directory}/vocab.json or tokenizer.json')
    for directory in VOICE_DIRS:
        graphs = [p for p in (root/directory).glob('*.onnx') if p.is_file()]
        if len(graphs) != 1 or graphs[0].stat().st_size == 0:
            missing.append(f'{directory}: exactly one nonempty *.onnx required')
        data = root/directory/'espeak-ng-data'
        if not data.is_dir() or not any(p.is_file() and p.stat().st_size > 0 and
                                       all(not part.startswith('.') for part in p.relative_to(data).parts)
                                       for p in data.rglob('*')):
            missing.append(f'{directory}/espeak-ng-data/ (nonempty runtime data required)')
    if missing:
        raise ValueError('Missing selected Phase-2 artifacts (no Tiny substitution, no automatic export):\n - ' + '\n - '.join(missing))


def _notice(path):
    if path.suffix.lower() in ('.zip', '.gz', '.bz2', '.xz', '.tar', '.7z', '.onnx', '.bin',
                               '.pcm', '.pt', '.pth', '.aar', '.apk', '.jar'):
        return False
    name = path.name.upper()
    if any(name == prefix or name.startswith(prefix + '.') or name.startswith(prefix + '-')
           for prefix in ('LICENSE', 'COPYING', 'NOTICE', 'MODEL_CARD')):
        return True
    if name in ('README', 'README.MD', 'README.TXT', 'README.RST'):
        return True
    return (any(part.upper() in ('LICENSES', 'NOTICES') for part in path.parts[:-1]) and
            path.suffix.lower() in ('.txt', '.md', '.rst', '.html', '.json'))


def selected_files(root, include_asr=True, fallback=None):
    """Read-only per-file selection, including legal metadata; no weight reads or exports."""
    root = root.resolve()
    validate(root, include_asr)
    selected = {}

    def add(path, base):
        relative = path.relative_to(base)
        if any(part.startswith('.') for part in relative.parts):
            return
        if not path.resolve().is_relative_to(base.resolve()):
            raise ValueError(f'Selected file escapes model cache: {relative}')
        selected[relative.as_posix()] = path

    for directory in ('vad', *ASR_DIRS, *MT_DIRS, *VOICE_DIRS, 'text_to_speech/cache'):
        base = root
        origin = base/directory
        if directory in ASR_DIRS and not origin.is_dir() and not include_asr and fallback is not None:
            base = fallback.resolve()
            origin = base/directory
        if not origin.is_dir():
            continue
        for path in origin.rglob('*'):
            if not path.is_file():
                continue
            relative = path.relative_to(origin)
            top = len(relative.parts) == 1
            runtime = False
            if directory == 'vad':
                runtime = top and path.name == 'silero_vad.onnx'
            elif directory in ASR_DIRS:
                runtime = top and path.name in ASR_NAMES | METADATA_NAMES
            elif directory in MT_DIRS:
                runtime = top and path.name in MT_NAMES | METADATA_NAMES
            elif directory in VOICE_DIRS:
                runtime = (relative.parts[0] == 'espeak-ng-data' or
                           (top and (path.suffix == '.onnx' or
                                     (path.name.endswith('.onnx.json') and path.with_suffix('').is_file()) or
                                     path.name == 'tokens.txt' or path.name in METADATA_NAMES)))
            else:
                runtime = top and path.name in {p + '.' + e for p in available_phrases(root) for e in ('pcm', 'sr')}
            if runtime or _notice(relative):
                add(path, base)
    for path in root.iterdir():
        if path.is_file() and _notice(Path(path.name)):
            add(path, root)
        elif path.is_dir() and path.name.upper() in ('LICENSES', 'NOTICES'):
            for child in path.rglob('*'):
                if child.is_file() and _notice(child.relative_to(root)):
                    add(child, root)
    return [(path, relative) for relative, path in sorted(selected.items())]


def _publish(staged, dst):
    # Backup is OUTSIDE the auto-cleaned staging directory, so a failed restore cannot delete it.
    backup = None
    if dst.exists():
        backup = Path(tempfile.mkdtemp(prefix='.vi-en-previous-', dir=dst.parent))
        try:
            dst.rename(backup/'models')
        except BaseException:
            backup.rmdir()
            raise
    try:
        shutil.move(str(staged), str(dst))
    except BaseException:
        if backup is not None:
            try:
                if dst.exists():
                    shutil.rmtree(dst)  # Only the failed publication's partial output.
                (backup/'models').rename(dst)
            except BaseException as restore_error:
                raise OSError(f'Publish/restore failed; prior bundle retained at {backup / "models"}') from restore_error
            backup.rmdir()
        raise
    if backup is not None:
        shutil.rmtree(backup)


def bundle(src, dst, include_asr=True):
    src, dst = src.resolve(), dst.resolve()
    if src == dst or src in dst.parents or dst in src.parents:
        raise ValueError('Source cache and bundle destination must be separate trees')
    if dst.exists() and not dst.is_dir():
        raise ValueError('Bundle destination must be a directory, not an existing file')
    files = selected_files(src, include_asr, fallback=dst)
    dst.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.vi-en-', dir=dst.parent) as tmp:
        staged = Path(tmp)/'models'
        staged.mkdir()
        for origin, relative in files:
            target = staged/relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(origin, target)
        (staged/'text_to_speech/cache/index.txt').write_text('\n'.join(available_phrases(staged)) + '\n')
        for directory in list(staged.rglob('*')):
            if directory.is_dir() and not any(directory.iterdir()):
                (directory/'.keep').touch()
        (staged/'profile.txt').write_text(PROFILE + '\n')
        (staged/'version.txt').write_text(PROFILE + '-' + datetime.now(timezone.utc).isoformat() + '\n')
        validate(staged, include_asr)
        (staged/'integrity.sha256').write_text(integrity_text(
            [(p, p.relative_to(staged).as_posix()) for p in staged.rglob('*') if payload_file(p, staged)]))
        _publish(staged, dst)
    for directory in ('vad', *ASR_DIRS, *MT_DIRS, *VOICE_DIRS, 'text_to_speech/cache'):
        size = sum(p.stat().st_size for p in (dst/directory).rglob('*') if p.is_file())
        print(f'{directory}: {size:,} bytes ({size / 1e6:.1f} MB)')
    print(f'{PROFILE}: {sum(p.stat().st_size for p in dst.rglob("*") if p.is_file()):,} bytes')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('--destination', type=Path)
    parser.add_argument('--prepare-asr', action='store_true', help='Stage existing non-ASR cache before creating missing selected ASRs; not a build gate')
    parser.add_argument('--profile', choices=[PROFILE], default=PROFILE)
    args = parser.parse_args()
    try:
        if args.destination:
            bundle(args.source, args.destination, include_asr=not args.prepare_asr)
        else:
            validate(args.source, include_asr=not args.prepare_asr)
            print(f'{PROFILE}: ' + ('non-ASR cache ready; full validation still required' if args.prepare_asr else 'selected cache complete'))
    except ValueError as error:
        parser.exit(1, str(error) + '\n')
