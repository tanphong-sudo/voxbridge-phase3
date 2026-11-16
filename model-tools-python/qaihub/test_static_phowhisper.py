import unittest
import torch
from transformers import WhisperConfig,WhisperForConditionalGeneration
from static_phowhisper import PhoEncoder,PhoDecoder

class PhoStaticCacheTest(unittest.TestCase):
 def test_all_positions_match_the_unmodified_checkpoint_decoder(self):
  torch.set_num_threads(1);torch.manual_seed(42)
  config=WhisperConfig(vocab_size=32,d_model=16,encoder_layers=1,decoder_layers=1,encoder_attention_heads=2,decoder_attention_heads=2,
   encoder_ffn_dim=32,decoder_ffn_dim=32,max_source_positions=16,max_target_positions=64,pad_token_id=0,bos_token_id=1,eos_token_id=2,decoder_start_token_id=1)
  model=WhisperForConditionalGeneration(config).eval();model.config._attn_implementation='eager'
  enc=PhoEncoder(model).eval();dec=PhoDecoder(model,history=31).eval()
  with torch.inference_mode():
   features=torch.randn(1,80,32);cross=enc(features);hidden=model.model.encoder(features,return_dict=False)[0]
   cache=[torch.zeros(2,1,8,31),torch.zeros(2,1,31,8),*cross]
   tokens=torch.tensor([[1]],dtype=torch.int32)
   for pos in range(12):
    mask=torch.full((1,1,1,32),-100.);mask[...,-(pos+1):]=0.
    actual=dec(tokens[:,-1:],mask,torch.tensor([pos],dtype=torch.int32),*cache)
    expected=model(encoder_outputs=(hidden,),decoder_input_ids=tokens,use_cache=False,return_dict=True).logits[:,-1:,:]
    torch.testing.assert_close(actual[0],expected,rtol=1e-4,atol=1e-4)
    cache=[actual[1],actual[2],*cross];tokens=torch.cat([tokens,torch.tensor([[3+pos]],dtype=torch.int32)],dim=1)
if __name__=='__main__':unittest.main()
