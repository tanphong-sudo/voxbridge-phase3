import unittest
from score_coverage_experiment import confusion
from coverage_experiment import PAIRS

class CoverageExperimentTest(unittest.TestCase):
    def test_confusion_counts_keep_false_alarms_and_misses(self):
        rows=[dict(label=label,diagnostic={'flag':flag}) for label,flag in [('bad',True),('bad',False),('good',True),('good',False)]]
        result=confusion(rows,lambda d:d['flag'])
        self.assertEqual((1,1,1,1),tuple(result[k] for k in ['tp','fp','fn','tn']))
        self.assertEqual(.5,result['precision']);self.assertEqual(.5,result['recall'])
    def test_no_observed_bad_cases_does_not_claim_perfect_recall(self):
        result=confusion([dict(label='good',diagnostic={'flag':False})],lambda d:d['flag'])
        self.assertIsNone(result['recall']);self.assertIsNone(result['precision'])
    def test_control_references_have_independent_second_proposition(self):
        self.assertEqual(20,len(PAIRS))
        for vi,en,compact_vi,compact_en in PAIRS:
            self.assertEqual(2,len(vi));self.assertEqual(2,len(en))
            self.assertTrue(compact_vi and compact_en)
            self.assertNotEqual(vi[0],vi[1]);self.assertNotEqual(en[0],en[1])

if __name__=='__main__':unittest.main()
