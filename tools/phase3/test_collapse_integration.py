import unittest
from verify_collapse_integration import replay
from unittest.mock import patch
from types import SimpleNamespace

class CollapseIntegrationRunnerTest(unittest.TestCase):
    def test_replay_rejects_missing_or_reordered_ids(self):
        rows=[{'id':'a'},{'id':'b'}]
        for output in ['{"id":"a"}\n','{"id":"b"}\n{"id":"a"}\n']:
            with patch('verify_collapse_integration.subprocess.run',return_value=SimpleNamespace(stdout=output)):
                with self.assertRaisesRegex(ValueError,'IDs mismatch'):
                    replay('java','cli','tests','json',rows)

    def test_replay_keeps_duplicate_texts_with_distinct_source_ids(self):
        rows=[{'id':'a','target_text':'yes'},{'id':'b','target_text':'yes'}]
        with patch('verify_collapse_integration.subprocess.run',return_value=SimpleNamespace(stdout='{"id":"a"}\n{"id":"b"}\n')):
            self.assertEqual(['a','b'],[r['id'] for r in replay('java','cli','tests','json',rows)])

if __name__=='__main__':unittest.main()
