import unittest
from score_full_audio import edit_distance,normalize
class FullAudioScorerTest(unittest.TestCase):
    def test_edit_distance_counts_omissions_and_insertions(self):
        self.assertEqual(2,edit_distance(['stop','and','wait'],['stop']))
        self.assertEqual(1,edit_distance(['stop'],['stop','now']))
        self.assertEqual(1,edit_distance(['open'],['close']))
        self.assertEqual(0,edit_distance([],[]))
    def test_normalization_preserves_vietnamese_negation_and_digits(self):
        self.assertEqual('không mở van 3',normalize('KHÔNG mở van 3!'))
if __name__=='__main__':unittest.main()
