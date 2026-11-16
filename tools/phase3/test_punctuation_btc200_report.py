import unittest
import tempfile
import json
from pathlib import Path
from unittest.mock import patch
from punctuation_btc200_report import main, NAMES

class PunctuationReportTest(unittest.TestCase):
    def test_report_escapes_corpus_and_model_text(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            for model in ['envit5','vinai']:
                d=root/model;d.mkdir()
                (d/'summary.json').write_text(json.dumps({'groups':{'all200':{'variants':{v:{'chrf':1.0} for v in NAMES}}}}))
                row={'id':'<script>alert(1)</script>','source_lang':'vi','source_text':'A < B','reference_target':'x','variants':{v:'<input>' for v in NAMES},'outputs':{v:'<img onerror=alert(1)>' for v in NAMES},'sentence_chrf':{v:1 for v in NAMES}}
                (d/'paired-details.jsonl').write_text(json.dumps(row)+'\n')
            with patch('sys.argv',['report','--root',folder]):main()
            text=(root/'comparison.html').read_text()
            self.assertNotIn('<img onerror=',text)
            self.assertIn('&lt;img onerror=',text)
            self.assertNotIn('<script>alert(1)',text)

if __name__=='__main__':unittest.main()
