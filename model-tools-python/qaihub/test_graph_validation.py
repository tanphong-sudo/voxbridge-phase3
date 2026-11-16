import unittest
from graph_validation import align_outputs

class OutputMappingTest(unittest.TestCase):
 def test_numbered_outputs_are_aligned_by_declared_index_not_dictionary_order(self):
  outputs={'output_2':['v'],'output_0':['logits'],'output_1':['k']}
  self.assertEqual(align_outputs(['logits','self_k','self_v'],outputs),[['logits'],['k'],['v']])
 def test_named_outputs_do_not_depend_on_dictionary_order(self):
  self.assertEqual(align_outputs(['logits','cache'],{'cache':[2],'logits':[1]}),[[1],[2]])
 def test_missing_or_unrecognized_output_is_not_silently_compared(self):
  with self.assertRaises(ValueError):align_outputs(['logits','cache'],{'output_0':[1]})
  with self.assertRaises(ValueError):align_outputs(['logits'],{'unknown':[1]})
if __name__=='__main__':unittest.main()
