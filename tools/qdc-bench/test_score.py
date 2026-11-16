import unittest
from score import summarize
class ScoreTest(unittest.TestCase):
 def test_wer_counts_deleted_word_and_keeps_token_caps_visible(self):
  rows=[dict(id='a',reference='Dừng máy số năm',hypothesis='Dừng máy năm',tokens=[1],token_cap=True,graph_run_ms=10,wall_ms=50)]
  result=summarize('phowhisper-small',rows,expected=100)
  self.assertEqual(result['word_errors'],1);self.assertEqual(result['reference_words'],4)
  self.assertEqual(result['wer_percent'],25);self.assertFalse(result['complete']);self.assertEqual(result['token_caps'],1)
 def test_duplicate_ids_cannot_claim_complete(self):
  r=dict(id='a',reference='one',hypothesis='one',tokens=[1],token_cap=False,graph_run_ms=10,wall_ms=50)
  with self.assertRaises(ValueError):summarize('phowhisper-small',[r,r],expected=2)
if __name__=='__main__':unittest.main()
