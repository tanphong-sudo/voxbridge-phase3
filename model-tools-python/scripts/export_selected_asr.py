"""Create only missing Phase-2 ASRs. Existing complete exports are never rebuilt."""
import argparse
import gc
import json
from pathlib import Path
import shutil
import tempfile
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools/dev'))
from model_integrity import promote_tree

MODELS = {
    'phowhisper-base-onnx': 'vinai/PhoWhisper-base',
    'whisper-small-onnx': 'openai/whisper-small',
}
OBSOLETE_ASR_DIRS = (
    'whisper-tiny-onnx', 'whisper-tiny-qnn',
    'whisper-base-onnx', 'whisper-base-qnn', 'whisper-small-qnn',
)
REQUIRED = ('encoder_model.onnx', 'decoder_model.onnx', 'decoder_with_past_model.onnx',
            'whisper_tokens.json', 'mel_80_201.f32.bin')


def valid_provenance(directory, expected_model):
    metadata = directory/'source.json'
    if not metadata.is_file():
        return False
    try:
        provenance = json.loads(metadata.read_text())
    except (OSError, json.JSONDecodeError):
        return False
    if provenance.get('model') != expected_model:
        return False
    if provenance.get('precision') != 'float32':
        return False
    revision = provenance.get('revision')
    if not isinstance(revision, str) or not revision.strip():
        return False
    if provenance.get('storage') == 'lossless shared ONNX external initializers' and not (directory/'shared_weights.bin').is_file():
        return False
    return True


def complete(directory, expected_model=None):
    expected_model = expected_model or MODELS.get(directory.name)
    if expected_model is None or not valid_provenance(directory, expected_model):
        return False
    return all((directory/f).is_file() and (directory/f).stat().st_size for f in REQUIRED) and any(
        (directory/f).is_file() for f in ('vocab.json', 'tokenizer.json'))


def export_one(root, name, model_id):
    destination = root/'speech_to_text'/name
    if complete(destination, model_id):
        print(f'REUSE {destination}', flush=True)
        return
    from optimum.exporters.onnx import main_export
    from transformers import AutoProcessor
    from huggingface_hub import HfApi
    from export_whisper_onnx import export_tokens, export_mel
    from share_onnx_weights import share_weights

    destination.parent.mkdir(parents=True, exist_ok=True)
    revision = HfApi().model_info(model_id).sha
    with tempfile.TemporaryDirectory(prefix=f'.{name}-', dir=destination.parent) as temp:
        raw, packed = Path(temp)/'raw', Path(temp)/'packed'
        print(f'EXPORT {model_id}@{revision} (FP32, unchanged decoder contract)', flush=True)
        main_export(model_id, output=raw, task='automatic-speech-recognition-with-past',
                    opset=17, device='cpu', revision=revision, no_post_process=True,
                    do_validation=True)
        gc.collect()
        AutoProcessor.from_pretrained(model_id, revision=revision).save_pretrained(raw)
        export_tokens(model_id, raw, revision=revision)
        export_mel(model_id, raw, revision=revision)
        share_weights(raw, packed)
        for file in raw.iterdir():
            if file.is_file() and file.suffix in ('.json', '.txt'):
                shutil.copy2(file, packed/file.name)
        shutil.copy2(raw/'mel_80_201.f32.bin', packed/'mel_80_201.f32.bin')
        (packed/'source.json').write_text(json.dumps({
            'model': model_id, 'revision': revision, 'precision': 'float32',
            'storage': 'lossless shared ONNX external initializers',
        }, indent=2) + '\n')
        if not complete(packed, model_id):
            raise RuntimeError(f'Export incomplete: {packed}')
        # Replace an incomplete previous export atomically. A failed promotion restores the old
        # directory so a Colab interruption never leaves the next Run-all permanently wedged.
        promote_tree(packed, destination)
        print(f'CREATED {destination}', flush=True)


def remove_obsolete_asrs(root):
    """Remove only superseded product ASR directories; never touch selected exports."""
    speech = root/'speech_to_text'
    for name in OBSOLETE_ASR_DIRS:
        path = speech/name
        if path.is_dir():
            shutil.rmtree(path)
            print(f'REMOVED obsolete ASR {path}', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('models', type=Path)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    if args.check:
        missing = [name for name, model_id in MODELS.items()
                   if not complete(args.models/'speech_to_text'/name, model_id)]
        print('Missing ASR:', ', '.join(missing) or 'none')
        raise SystemExit(bool(missing))
    remove_obsolete_asrs(args.models)
    for name, model_id in MODELS.items():
        export_one(args.models, name, model_id)
