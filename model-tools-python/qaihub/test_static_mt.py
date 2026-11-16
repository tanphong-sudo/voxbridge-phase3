import unittest
import torch
from transformers import MBartConfig,MBartForConditionalGeneration,T5Config,T5ForConditionalGeneration
from static_mt import StaticEncoder,StaticDecoder,empty_cache,SOURCE_LENGTH,TARGET_LENGTH,BEAMS

class StaticCacheParityTest(unittest.TestCase):
 def test_complete_cache_matches_variable_length_decoder_across_positions(self):
  torch.set_num_threads(1)
  torch.manual_seed(42)
  models=[('vinai',MBartForConditionalGeneration(MBartConfig(vocab_size=32,d_model=16,encoder_layers=1,decoder_layers=1,
   encoder_attention_heads=2,decoder_attention_heads=2,encoder_ffn_dim=32,decoder_ffn_dim=32,max_position_embeddings=256,pad_token_id=1))),
   ('envit5',T5ForConditionalGeneration(T5Config(vocab_size=32,d_model=16,d_kv=8,d_ff=32,num_layers=1,num_decoder_layers=1,num_heads=2,pad_token_id=0,decoder_start_token_id=0)))]
  for kind,model in models:
   with self.subTest(kind=kind),torch.inference_mode():
    model.eval();model.config._attn_implementation='eager'
    encoder=StaticEncoder(model,kind).eval();decoder=StaticDecoder(model,kind).eval()
    ids=torch.full((1,SOURCE_LENGTH),3,dtype=torch.int32);mask=torch.zeros_like(ids);mask[:,:7]=1
    result=encoder(ids,mask);hidden=result[0].repeat(BEAMS,1,1)
    crossmask=(1-mask[:,None,None,:].float()).repeat(BEAMS,1,1,1)*-10000.
    cache=[]
    for i,(k,v) in enumerate(empty_cache(model,kind)):cache.extend([k,v,result[1+i*2].repeat(BEAMS,1,1,1),result[2+i*2].repeat(BEAMS,1,1,1)])
    tokens=torch.tensor([[2],[2]],dtype=torch.int32)
    for position in range(12):
     selfmask=torch.full((BEAMS,1,1,TARGET_LENGTH),-10000.);selfmask[...,-(position+1):]=0.
     actual=decoder(tokens[:,-1:],torch.full((BEAMS,1),position,dtype=torch.int32),selfmask,crossmask,*cache)
     expected=model(encoder_outputs=(hidden,),attention_mask=mask.repeat(BEAMS,1),decoder_input_ids=tokens,use_cache=False,return_dict=True).logits[:,-1:,:]
     torch.testing.assert_close(actual[0],expected,rtol=1e-4,atol=1e-4)
     for i in range(len(cache)//4):cache[i*4]=actual[1+i*2];cache[i*4+1]=actual[2+i*2]
     tokens=torch.cat([tokens,torch.tensor([[3+position%10],[4+position%10]],dtype=torch.int32)],dim=1)
if __name__=='__main__':unittest.main()
