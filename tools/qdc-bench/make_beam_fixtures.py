import json,sys
from pathlib import Path
import numpy as np,torch
from transformers import MBartConfig,MBartForConditionalGeneration,T5Config,T5ForConditionalGeneration,GenerationConfig
sys.path.insert(0,str(Path(__file__).resolve().parent))
from test_mt_runtime import TorchBridge
from mt_runtime import RemoteMt
from static_mt import empty_cache

def main():
 torch.set_num_threads(1);fixtures=[]
 for seed in [1,31,42,91]:
  torch.manual_seed(seed)
  for kind,model in [('vinai',MBartForConditionalGeneration(MBartConfig(vocab_size=32,d_model=16,encoder_layers=1,decoder_layers=1,encoder_attention_heads=2,decoder_attention_heads=2,encoder_ffn_dim=32,decoder_ffn_dim=32,max_position_embeddings=256,pad_token_id=1,bos_token_id=0,eos_token_id=2,decoder_start_token_id=2))),('envit5',T5ForConditionalGeneration(T5Config(vocab_size=32,d_model=16,d_kv=8,d_ff=32,num_layers=1,num_decoder_layers=1,num_heads=2,pad_token_id=0,eos_token_id=1,decoder_start_token_id=0)))]:
   with torch.inference_mode():
    model.eval();model.config._attn_implementation='eager';bridge=TorchBridge(model,kind);history=[];run=bridge.run
    def record(session,feeds):
     result=run(session,feeds)
     if session=='decoder':history.append(dict(input_ids=feeds['input_ids'].tolist(),logits=bridge.get(result['logits'])[:,0,:].tolist()))
     return result
    bridge.run=record;shape=list(empty_cache(model,kind)[0][0].shape)
    runtime=RemoteMt(model.config,GenerationConfig.from_model_config(model.config),bridge,{**{n:{} for n in bridge.dec_names},**{f'cache_0_self_{k}':dict(shape=shape) for k in ['k','v']}})
    ids=torch.full((1,64),3,dtype=torch.int64);mask=torch.zeros_like(ids);mask[:,:9]=1
    opts=dict(num_beams=2,max_new_tokens=16,do_sample=False,early_stopping=kind=='vinai')
    original=model.generate(input_ids=ids,attention_mask=mask,**opts);actual=runtime.generate(input_ids=ids,attention_mask=mask,**opts);assert original.tolist()==actual.tolist()
    forced=model.generation_config.forced_eos_token_id
    fixtures.append(dict(start=model.config.decoder_start_token_id,eos=model.config.eos_token_id,forced_eos=forced if forced is not None else -1,max=16,early=kind=='vinai',steps=history,expected=original[0].tolist()))
 Path(sys.argv[1]).write_text(json.dumps(fixtures));print('FIXTURES',len(fixtures))
if __name__=='__main__':main()
