import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / 'model-tools-python/qaihub/prepare_whisper.py'
spec = importlib.util.spec_from_file_location('qnn_prepare', SCRIPT)
qnn = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qnn)


class QnnBundleTest(unittest.TestCase):
    def fixture(self, root):
        metadata = dict(model_id='whisper_small_quantized', runtime='precompiled_qnn_onnx',
            precision='w8a16', tool_versions=dict(onnx_runtime='1.30.0', qairt='2.50.0'))
        payload = json.dumps(metadata).encode()
        (root / 'metadata.json').write_bytes(payload)
        return dict(model_id=metadata['model_id'], runtime=metadata['runtime'], precision='w8a16',
            required_ort='1.30.0', required_qairt='2.50.0', soc='sm8650',
            files=[dict(path='metadata.json', bytes=len(payload), sha256=hashlib.sha256(payload).hexdigest())])

    def test_profile_candidate_does_not_claim_android_validation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            result = qnn.verify_bundle(root, self.fixture(root))
            self.assertFalse(result['android_runtime_validated'])
            self.assertFalse(result['runtime_compatible'])

    def test_wrong_runtime_and_soc_are_explicit_errors(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            data = self.fixture(root)
            with self.assertRaisesRegex(ValueError, 'ORT'):
                qnn.verify_bundle(root, data, runtime_version='1.20.0', soc='sm8650')
            with self.assertRaisesRegex(ValueError, 'SoC'):
                qnn.verify_bundle(root, data, runtime_version='1.30.0', soc='sm8550')

    def test_tampered_artifact_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            data = self.fixture(root)
            (root / 'metadata.json').write_bytes(b'corrupt')
            with self.assertRaisesRegex(ValueError, 'integrity'):
                qnn.verify_bundle(root, data)

    def test_manifest_cannot_read_outside_bundle(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            data = self.fixture(root)
            data['files'][0]['path'] = '../outside'
            with self.assertRaisesRegex(ValueError, 'path'):
                qnn.verify_bundle(root, data)
