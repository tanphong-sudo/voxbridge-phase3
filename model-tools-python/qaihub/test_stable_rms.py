import unittest,torch
from transformers.models.t5.modeling_t5 import T5LayerNorm
from stable_rms import StableRmsNorm
class StableNormTest(unittest.TestCase):
 def test_original_math_preserved_for_large_activations_without_fp16_square_overflow(self):
  torch.manual_seed(42);original=T5LayerNorm(768,eps=1e-6);norm=StableRmsNorm(original)
  x=torch.randn(1,64,768)*600;x[0,0,0]=5000
  with torch.inference_mode():
   expected=original(x);actual=norm(x);torch.testing.assert_close(actual,expected,rtol=1e-5,atol=1e-5)
   half=norm.half()(x.half());self.assertTrue(torch.isfinite(half).all());torch.testing.assert_close(half.float(),expected,rtol=5e-3,atol=5e-3)
if __name__=='__main__':unittest.main()
