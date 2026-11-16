from contextlib import redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[2] / 'model-tools-python/qaihub'
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location('qnn_profile', SCRIPTS / 'profile_whisper.py')
profile_tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(profile_tool)


class QnnProfileTest(unittest.TestCase):
    def test_resume_preserves_completed_raw_snapshot_without_redownload(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            ledger = root / 'jobs.json'
            ledger.write_text(json.dumps([dict(part='encoder', job_id='recorded', job_url='https://example.test/job')]))
            raw = root / 'encoder-profile.json'
            snapshot = dict(execution_summary=dict(all_inference_times=[1000, 2000, 3000],
                estimated_inference_time=1000, estimated_inference_peak_memory=1048576),
                execution_detail=[dict(compute_unit='NPU')])
            raw.write_text(json.dumps(snapshot))
            before = raw.read_bytes()
            job = types.SimpleNamespace(get_status=lambda: types.SimpleNamespace(success=True, code='SUCCESS'),
                download_profile=lambda: self.fail('Completed raw evidence must not be re-downloaded'))
            fake = types.SimpleNamespace(get_job=lambda job_id: job)
            with patch.dict(sys.modules, {'qai_hub': fake}), patch.object(sys, 'argv', ['profile', '--jobs', str(ledger)]), redirect_stdout(io.StringIO()):
                profile_tool.main()
            self.assertEqual(before, raw.read_bytes())
            summary = json.loads((root / 'profile-summary.json').read_text())
            self.assertEqual(summary['jobs'][0]['median_ms'], 2.0)

    def test_failed_write_preserves_previous_ledger(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'jobs.json'
            path.write_text('["previous-job"]')
            before = path.read_bytes()
            with patch.object(Path, 'write_text', side_effect=OSError('disk full')):
                with self.assertRaises(OSError):
                    profile_tool.save_json(path, ['new-job'])
            self.assertEqual(before, path.read_bytes())
