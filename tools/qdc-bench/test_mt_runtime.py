"""Catch beam-cache reorder mistakes by comparing whole sequences to original checkpoints."""
import sys,unittest
from pathlib import Path
import numpy as np
import torch
from transformers import MBartConfig,MBartForConditionalGeneration,T5Config,T5ForConditionalGeneration,GenerationConfig
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'model-tools-python/qaihub'))
from static_mt import StaticEncoder,StaticDecoder,empty_cache,SOURCE_LENGTH
from mt_runtime import RemoteMt

class TorchBridge:
    def __init__(self,model,kind):
        self.enc=StaticEncoder(model,kind);self.dec=StaticDecoder(model,kind);self.data={};self.next=0
        self.dec_names=['input_ids','position_ids','self_mask','cross_mask']+[f'cache_{i}_{x}' for i in range(1) for x in ['self_k','self_v','cross_k','cross_v']]
    def reset(self):self.data={}
    def put(self,a):self.next+=1;key=str(self.next);self.data[key]=np.array(a);return key
    def zeros(self,shape):return self.put(np.zeros(shape,dtype=np.float32))
    def get(self,h):return self.data[h]
    def gather(self,h,indices):return self.put(self.data[h][indices])
    def drop(self,hs):
        for h in hs:self.data.pop(h,None)
    def run(self,session,feeds):
        if session=='encoder':
            out=self.enc(torch.from_numpy(feeds['input_ids']),torch.from_numpy(feeds['attention_mask']));names=['encoder_hidden_states','cross_0_k','cross_0_v']
        else:
            args=[torch.from_numpy(self.data[feeds[n]] if isinstance(feeds[n],str) else feeds[n]) for n in self.dec_names]
            out=self.dec(*args);names=['logits','present_0_k','present_0_v']
        return {name:self.put(x.detach().numpy()) for name,x in zip(names,out)}

class RemoteGenerationTest(unittest.TestCase):
    def test_beam_two_complete_tokens_equal_original_t5_and_mbart(self):
        torch.set_num_threads(1);torch.manual_seed(31)
        models=[('vinai',MBartForConditionalGeneration(MBartConfig(vocab_size=32,d_model=16,encoder_layers=1,decoder_layers=1,encoder_attention_heads=2,decoder_attention_heads=2,encoder_ffn_dim=32,decoder_ffn_dim=32,max_position_embeddings=256,pad_token_id=1,bos_token_id=0,eos_token_id=2,decoder_start_token_id=2))),
                ('envit5',T5ForConditionalGeneration(T5Config(vocab_size=32,d_model=16,d_kv=8,d_ff=32,num_layers=1,num_decoder_layers=1,num_heads=2,pad_token_id=0,eos_token_id=1,decoder_start_token_id=0)))]
        for kind,model in models:
            with self.subTest(kind=kind),torch.inference_mode():
                model.eval();model.config._attn_implementation='eager';bridge=TorchBridge(model,kind)
                shape=list(empty_cache(model,kind)[0][0].shape)
                runtime=RemoteMt(model.config,GenerationConfig.from_model_config(model.config),bridge,{**{n:{} for n in bridge.dec_names},**{f'cache_0_self_{k}':dict(shape=shape) for k in ['k','v']}})
                ids=torch.full((1,64),3,dtype=torch.int64);mask=torch.zeros_like(ids);mask[:,:9]=1
                opts=dict(num_beams=2,max_new_tokens=16,do_sample=False)
                expected=model.generate(input_ids=ids,attention_mask=mask,**opts)
                actual=runtime.generate(input_ids=ids,attention_mask=mask,**opts)
                self.assertEqual(actual.tolist(),expected.tolist())
                self.assertLessEqual(len(bridge.data),4,'Old decoder tensors leaked between tokens')
if __name__=='__main__':unittest.main()
