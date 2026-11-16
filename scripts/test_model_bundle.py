import importlib.util
import json
from pathlib import Path
import tempfile
import shutil
import unittest

ROOT = Path(__file__).resolve().parents[1]

class BundleTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('bundle', ROOT / 'tools/dev/model_bundle.py')
        self.m = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.m)
        selected_spec = importlib.util.spec_from_file_location(
            'selected_asr', ROOT / 'model-tools-python/scripts/export_selected_asr.py')
        self.selected = importlib.util.module_from_spec(selected_spec)
        selected_spec.loader.exec_module(self.selected)

    def test_selected_asr_allowlist_is_exact(self):
        self.assertEqual(self.m.ASR_DIRS, (
            'speech_to_text/phowhisper-base-onnx',
            'speech_to_text/whisper-small-onnx',
        ))

    def test_selected_asr_exporter_removes_only_obsolete_asrs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            speech = root / 'speech_to_text'
            keep = speech / 'whisper-small-onnx'
            keep.mkdir(parents=True)
            (keep / 'marker').write_text('keep')
            for name in self.selected.OBSOLETE_ASR_DIRS:
                (speech / name).mkdir(parents=True)
                (speech / name / 'marker').write_text('remove')
            self.selected.remove_obsolete_asrs(root)
            self.assertTrue((keep / 'marker').is_file())
            self.assertTrue(all(not (speech / name).exists() for name in self.selected.OBSOLETE_ASR_DIRS))

    def fixture(self, root):
        for name in self.m.required_files():
            p = root / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(b'fixture')
        for name in self.m.ASR_DIRS:
            (root / name / 'vocab.json').write_text('{}')
        expected_models = {
            'speech_to_text/phowhisper-base-onnx': 'vinai/PhoWhisper-base',
            'speech_to_text/whisper-small-onnx': 'openai/whisper-small',
        }
        for directory, model_id in expected_models.items():
            (root / directory / 'source.json').write_text(json.dumps({
                'model': model_id,
                'revision': 'fixture-revision',
                'precision': 'float32',
                'storage': 'regular ONNX files',
            }))
        for name in self.m.VOICE_DIRS:
            (root / name / 'model.onnx').write_bytes(b'voice')
            (root / name / 'espeak-ng-data').mkdir()
            (root / name / 'espeak-ng-data/phontab').write_bytes(b'data')
        for name in self.m.MT_DIRS:
            (root / name / 'encoder_model.onnx').write_bytes(b'unused fp32')
        (root / 'text_to_speech/cache/index.txt').write_text('\n'.join(self.m.PHRASES))
        p = root / 'translation/opus-mt-en-zh/model.onnx'
        p.parent.mkdir(parents=True)
        p.write_bytes(b'excluded')

    def test_allowlist_preserves_source_and_cleans_previous_bundle(self):
        with tempfile.TemporaryDirectory() as tmp:
            src, dst = Path(tmp)/'src', Path(tmp)/'dst'
            self.fixture(src)
            dst.mkdir()
            (dst/'old-zh.onnx').write_bytes(b'old')
            self.m.bundle(src, dst)
            self.assertFalse((dst/'old-zh.onnx').exists())
            self.assertFalse((dst/'translation/opus-mt-en-zh').exists())
            self.assertFalse((dst/self.m.MT_DIRS[0]/'encoder_model.onnx').exists())
            self.assertTrue((src/self.m.MT_DIRS[0]/'encoder_model.onnx').exists())
            self.assertTrue((dst/self.m.ASR_DIRS[1]/'encoder_model.onnx').exists())
            self.assertEqual((dst/'profile.txt').read_text().strip(), 'SMOKE_VI_EN')

    def test_prepare_retains_new_local_asr_on_rerun(self):
        with tempfile.TemporaryDirectory() as tmp:
            src, dst = Path(tmp)/'src', Path(tmp)/'dst'
            self.fixture(src)
            self.m.bundle(src, dst)
            shutil.rmtree(src/self.m.ASR_DIRS[0])
            self.m.bundle(src, dst, include_asr=False)
            self.assertTrue((dst/self.m.ASR_DIRS[0]/'encoder_model.onnx').is_file())

    def test_shared_weights_sidecar_is_required(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp)/'src'
            self.fixture(src)
            (src/self.m.ASR_DIRS[0]/'source.json').write_text(json.dumps({
                'model': 'vinai/PhoWhisper-base',
                'revision': 'fixture-revision',
                'precision': 'float32',
                'storage': 'lossless shared ONNX external initializers',
            }))
            with self.assertRaisesRegex(ValueError, 'shared_weights.bin'):
                self.m.validate(src)

    def test_selected_asr_provenance_is_required_and_exact(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp)/'src'
            self.fixture(src)
            provenance = src/self.m.ASR_DIRS[1]/'source.json'
            provenance.unlink()
            with self.assertRaisesRegex(ValueError, 'source.json'):
                self.m.validate(src)

            provenance.write_text(json.dumps({
                'model': 'openai/whisper-tiny',
                'revision': 'wrong-model',
                'precision': 'float32',
                'storage': 'regular ONNX files',
            }))
            with self.assertRaisesRegex(ValueError, 'openai/whisper-small'):
                self.m.validate(src)

    def test_selected_asr_provenance_requires_fp32_and_revision(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp)/'src'
            self.fixture(src)
            provenance = src/self.m.ASR_DIRS[1]/'source.json'
            provenance.write_text(json.dumps({
                'model': 'openai/whisper-small',
                'revision': 'fixture-revision',
                'precision': 'float16',
                'storage': 'regular ONNX files',
            }))
            with self.assertRaisesRegex(ValueError, 'precision=float32'):
                self.m.validate(src)

            provenance.write_text(json.dumps({
                'model': 'openai/whisper-small',
                'revision': '',
                'precision': 'float32',
                'storage': 'regular ONNX files',
            }))
            with self.assertRaisesRegex(ValueError, 'source.json revision'):
                self.m.validate(src)

    def test_selected_asr_exporter_rejects_renamed_wrong_model_cache(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)/'whisper-small-onnx'
            directory.mkdir()
            for name in self.selected.REQUIRED:
                (directory/name).write_bytes(b'fixture')
            (directory/'vocab.json').write_text('{}')
            (directory/'source.json').write_text(json.dumps({
                'model': 'openai/whisper-tiny',
                'revision': 'wrong-model',
                'precision': 'float32',
                'storage': 'regular ONNX files',
            }))
            self.assertFalse(self.selected.complete(directory))

    def test_selected_asr_exporter_validates_temporary_packed_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)/'packed'
            directory.mkdir()
            for name in self.selected.REQUIRED:
                (directory/name).write_bytes(b'fixture')
            (directory/'vocab.json').write_text('{}')
            (directory/'source.json').write_text(json.dumps({
                'model': 'openai/whisper-small',
                'revision': 'fixture-revision',
                'precision': 'float32',
                'storage': 'regular ONNX files',
            }))
            self.assertTrue(self.selected.complete(directory, 'openai/whisper-small'))

    def test_missing_selected_asr_fails_before_replacing_bundle(self):
        with tempfile.TemporaryDirectory() as tmp:
            src, dst = Path(tmp)/'src', Path(tmp)/'dst'
            self.fixture(src)
            (src/self.m.ASR_DIRS[1]/'decoder_model.onnx').unlink()
            dst.mkdir()
            (dst/'keep').write_text('previous bundle')
            with self.assertRaisesRegex(ValueError, 'whisper-small-onnx/decoder_model.onnx'):
                self.m.bundle(src, dst)
            self.assertTrue((dst/'keep').exists())

if __name__ == '__main__': unittest.main()
