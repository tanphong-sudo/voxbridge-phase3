from pathlib import Path
import hashlib
import json
import tempfile
import types
import unittest
from unittest.mock import patch

import vieneu_bridge as vb


class VieneuBridgeTest(unittest.TestCase):
    def artifacts(self, root):
        artifacts = root / 'nano'
        artifacts.mkdir()
        rows = []
        for name in vb.REQUIRED_FILES:
            data = name.encode()
            (artifacts / name).write_bytes(data)
            rows.append(dict(path=name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
        manifest = root / 'manifest.json'
        manifest.write_text(json.dumps(dict(repo=vb.REPO, revision=vb.REVISION, downloaded_files=rows)))
        return artifacts, manifest

    def test_corrupt_weights_rejected_before_loading_sdk(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            artifacts, manifest = self.artifacts(root)
            (artifacts / vb.REQUIRED_FILES[0]).write_bytes(b'corrupt')
            with patch.object(vb, '_new_engine') as factory:
                with self.assertRaisesRegex(RuntimeError, 'integrity'):
                    vb.HostVieneuTts(root, artifacts, manifest)
                factory.assert_not_called()

    def test_missing_manifest_entry_and_wrong_revision_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            artifacts, manifest = self.artifacts(root)
            data = json.loads(manifest.read_text())
            data['downloaded_files'].pop()
            manifest.write_text(json.dumps(data))
            with self.assertRaisesRegex(RuntimeError, 'inventory'):
                vb.HostVieneuTts(root, artifacts, manifest)
            data['revision'] = 'wrong'
            manifest.write_text(json.dumps(data))
            with self.assertRaisesRegex(RuntimeError, 'revision'):
                vb.HostVieneuTts(root, artifacts, manifest)

    def test_vietnamese_uses_one_cpu_engine_native_rate_and_explicit_seed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            artifacts, manifest = self.artifacts(root)
            calls = []
            fake = types.SimpleNamespace(infer=lambda text, **kw: calls.append((text, kw)) or [0., .2, -.2])
            with patch.object(vb, '_new_engine', return_value=fake) as factory:
                engine = vb.HostVieneuTts(root, artifacts, manifest)
                first = engine.synthesize('Không mở van.', 'vi', root / 'a.wav')
                engine.synthesize('Nói lại.', 'vi', root / 'b.wav')
            self.assertEqual(factory.call_count, 1)
            self.assertEqual(first.sample_rate, 24000)
            self.assertEqual(first.backend, 'vieneu-nano-cpu-python')
            self.assertEqual(calls[0][1], dict(voice='Trúc Ly', steps=8, seed=42))

    def test_english_remains_piper_and_invalid_input_does_not_load_nano(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            artifacts, manifest = self.artifacts(root)
            engine = vb.HostVieneuTts(root, artifacts, manifest)
            with patch.object(vb, '_new_engine') as factory, patch.object(vb.tts_bridge, 'synthesize', return_value='english') as piper:
                self.assertEqual(engine.synthesize('Stop.', 'en', root / 'en.wav'), 'english')
                piper.assert_called_once_with(root, 'en', 'Stop.', root / 'en.wav')
                for text, lang in [('', 'vi'), ('text', 'zh')]:
                    with self.assertRaises(ValueError):
                        engine.synthesize(text, lang, root / 'invalid.wav')
                factory.assert_not_called()

    def test_empty_or_nonfinite_audio_cannot_create_successful_wav(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            artifacts, manifest = self.artifacts(root)
            for samples in [[], [float('nan')], [float('inf')]]:
                fake = types.SimpleNamespace(infer=lambda *a, **kw: samples)
                with patch.object(vb, '_new_engine', return_value=fake):
                    engine = vb.HostVieneuTts(root, artifacts, manifest)
                    with self.assertRaisesRegex(RuntimeError, 'audio'):
                        engine.synthesize('Xin chào.', 'vi', root / 'bad.wav')
                    self.assertFalse((root / 'bad.wav').exists())


if __name__ == '__main__':
    unittest.main()
