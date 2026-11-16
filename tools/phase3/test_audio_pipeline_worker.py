import unittest
from audio_pipeline_worker import decide_generation, cached_prompt_path, pcm16

class AudioPipelineWorkerTest(unittest.TestCase):
    def test_vinai_decode_keeps_language_start_and_beam2(self):
        class Tok:lang_code_to_id={'vi_VN':1,'en_XX':2}
        opts=decide_generation('vinai',Tok(),'en')
        self.assertEqual(2,opts['decoder_start_token_id']);self.assertIsNone(opts['forced_bos_token_id']);self.assertEqual(2,opts['num_beams'])
    def test_prompt_ids_are_fixed_and_cannot_escape_output_root(self):
        self.assertEqual('ask_repeat_vi',cached_prompt_path('ASK_REPEAT','vi'))
        with self.assertRaises(ValueError):cached_prompt_path('../secret','vi')
        with self.assertRaises(ValueError):cached_prompt_path('NO_SPEECH','../../x')
    def test_pcm_scaling_matches_app(self):
        self.assertEqual([-32767,0,32767],pcm16([-1.,0.,1.]))

if __name__=='__main__':unittest.main()
