import unittest
from punctuation_ablation import cache_key, collect_cache, validate_results

class PunctuationAblationTest(unittest.TestCase):
    def test_identical_text_in_different_languages_has_different_cache_key(self):
        self.assertNotEqual(cache_key("OK.", "vi"), cache_key("OK.", "en"))

    def test_cache_never_merges_case_or_punctuation_variants(self):
        self.assertNotEqual(cache_key('anh đến chưa.'),cache_key('Anh đến chưa?'))
        self.assertEqual(cache_key('Máy A-12.'),cache_key('Máy A-12.'))

    def test_cache_rejects_nonmatching_generation_settings(self):
        meta={'num_beams':1,'max_new_tokens':192}
        with self.assertRaisesRegex(ValueError,'settings'):
            collect_cache([(meta,[{'source_text':'Hi','hypothesis':'Xin chào'}])])

    def test_cache_does_not_reuse_failed_or_capped_outputs(self):
        meta={'num_beams':2,'max_new_tokens':192,'dynamic_int8':False}
        rows=[dict(source_text='A',hypothesis='B',failure='error',stop_reason='eos'),dict(source_text='C',hypothesis='D',failure=None,stop_reason='token_cap')]
        self.assertEqual({},collect_cache([(meta,rows)]))

    def test_validation_rejects_duplicate_or_missing_outputs(self):
        with self.assertRaises(ValueError):validate_results([{'id':'a'},{'id':'a'}],['a'])
        with self.assertRaises(ValueError):validate_results([{'id':'a'}],['a','b'])

if __name__=='__main__':unittest.main()
