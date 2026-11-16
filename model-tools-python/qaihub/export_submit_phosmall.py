#!/usr/bin/env python3
"""Full static-cache PhoWhisper-small graphs; gate against original decoder before AI Hub jobs."""
import argparse,json,sys,gc
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);a=p.parse_args()
 import torch,numpy as np,onnx,onnxruntime as ort,qai_hub as hub
 from qai_hub.client import ClientConfig
 from transformers import WhisperForConditionalGeneration,WhisperFeatureExtractor
 from static_phowhisper import PhoEncoder,PhoDecoder
 client=hub.Client(ClientConfig(**json.loads(sys.stdin.readline())));root=a.root;torch.set_num_threads(4);torch.set_num_interop_threads(1)
 source=json.loads((root/'phowhisper-small-selected/source.json').read_text());model=WhisperForConditionalGeneration.from_pretrained(source['snapshot'],local_files_only=True,use_safetensors=False,attn_implementation='eager').eval()
 encoder=PhoEncoder(model).eval();decoder=PhoDecoder(model).eval();out=root/'phowhisper-small-static';out.mkdir(exist_ok=True)
 extractor=WhisperFeatureExtractor.from_pretrained(source['snapshot'],local_files_only=True)
 import wave
 corpus=[json.loads(x) for x in (root/'full-audio-btc200/manifest200.jsonl').read_text().splitlines()];corpus=[r for r in corpus if r['source_lang']=='vi']
 with wave.open(corpus[0]['wav'],'rb') as w:pcm=np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').astype(np.float32)/32768
 features=torch.from_numpy(extractor(pcm,sampling_rate=16000,return_tensors='np')['input_features'])
 with torch.inference_mode():
  cross=encoder(features);hidden=model.model.encoder(features,return_dict=False)[0]
  cache=[]
  for i in range(model.config.decoder_layers):cache.extend([torch.zeros(12,1,64,199),torch.zeros(12,1,199,64),cross[i*2],cross[i*2+1]])
  prefix=torch.tensor([[50258,50278,50359,50363]],dtype=torch.int32);checks=[]
  for pos in range(12):
   token=prefix[:,pos:pos+1] if pos<4 else torch.tensor([[next_token]],dtype=torch.int32)
   if pos>=4:prefix=torch.cat([prefix,token],dim=1)
   mask=torch.full((1,1,1,200),-100.);mask[...,-(pos+1):]=0.
   actual=decoder(token,mask,torch.tensor([pos],dtype=torch.int32),*cache)
   expected=model(encoder_outputs=(hidden,),decoder_input_ids=prefix[:,:pos+1],use_cache=False,return_dict=True).logits[:,-1:,:]
   torch.testing.assert_close(actual[0],expected,rtol=5e-3,atol=5e-3);checks.append(float((actual[0]-expected).abs().max()))
   next_token=int(actual[0].reshape(-1).argmax())
   for i in range(12):cache[i*4]=actual[1+i*2];cache[i*4+1]=actual[2+i*2]
 print('PHOSMALL_STATIC_TORCH_PARITY',max(checks),flush=True)
 allinputs=(torch.tensor([[50363]],dtype=torch.int32),torch.cat([torch.full((1,1,1,199),-100.),torch.zeros(1,1,1,1)],dim=-1),torch.tensor([0],dtype=torch.int32),*cache)
 ledger={}
 for part,wrapper,inputs,names,outputs in [
  ('encoder',encoder,(features,),['input_features'],[f'{k}_cache_cross_{i}' for i in range(12) for k in ['k','v']]),
  ('decoder',decoder,allinputs,['input_ids','attention_mask','position_ids']+[f'{k}_cache_{side}_{i}'+('_in' if side=='self' else '') for i in range(12) for side in ['self','cross'] for k in ['k','v']],
   ['logits']+[f'{k}_cache_self_{i}_out' for i in range(12) for k in ['k','v']])]:
  folder=out/part;folder.mkdir(exist_ok=True);file=folder/'model.onnx';print('PHOSMALL_EXPORT',part,flush=True)
  with torch.inference_mode():expected=wrapper(*inputs);torch.onnx.export(wrapper,inputs,str(file),input_names=names,output_names=outputs,opset_version=17,dynamo=False,do_constant_folding=True)
  m=onnx.load(str(file));data=folder/'model.data'
  if data.exists():data.unlink()
  onnx.save_model(m,str(file),save_as_external_data=True,all_tensors_to_one_file=True,location='model.data',size_threshold=0);del m;gc.collect()
  opts=ort.SessionOptions();opts.intra_op_num_threads=3;opts.inter_op_num_threads=1;session=ort.InferenceSession(str(file),sess_options=opts,providers=['CPUExecutionProvider'])
  feed={n:x.numpy() for n,x in zip(names,inputs)};actual=session.run(None,feed)
  for x,y in zip(actual,expected):np.testing.assert_allclose(x,y.numpy(),rtol=5e-3,atol=5e-3)
  ledger[part]=dict(model=source['model'],revision=source['revision'],input_specs={n:[list(t.shape),str(t.dtype).removeprefix('torch.')] for n,t in zip(names,inputs)},outputs=outputs,torch_step_parity_max=max(checks),ort_parity=True)
  print('PHOSMALL_ORT_PARITY',part,flush=True)
  job=client.submit_compile_job(str(folder),device=hub.Device('Samsung Galaxy S24 Ultra'),options='--target_runtime qnn_dlc',name='1Voice PhoWhisper-SMALL selected '+part+' full-cache200',retry=False)
  ledger[part].update(job_id=job.job_id,url=job.url);(out/'jobs.json').write_text(json.dumps(ledger,indent=2)+'\n');print('PHOSMALL_SUBMITTED',part,job.job_id,flush=True)
  del session
 (out/'source.json').write_text(json.dumps(source,indent=2)+'\n');print('PHOSMALL_READY',flush=True)
if __name__=='__main__':main()
