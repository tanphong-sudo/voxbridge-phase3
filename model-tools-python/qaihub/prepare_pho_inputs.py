#!/usr/bin/env python3
"""Reuse existing compiled PhoWhisper source contract; real WAV mel/caches, no ASR model switch."""
import argparse,json,sys,zipfile
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--meta',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--models',type=Path,required=True);a=p.parse_args()
 import numpy as np,onnxruntime as ort,qai_hub as hub
 from qai_hub.client import ClientConfig
 client=hub.Client(ClientConfig(**json.loads(sys.stdin.readline())))
 a.out.mkdir(parents=True,exist_ok=True);meta=json.loads(a.meta.read_text());sessions={};paths={}
 for part,row in meta.items():
  dest=a.out/'exports'/part;dest.mkdir(parents=True,exist_ok=True)
  graphs=list(dest.rglob('*.onnx'))
  if not graphs:
   file=Path(client.get_model(row['model_id']).download(str(dest/'source')))
   if zipfile.is_zipfile(file):
    with zipfile.ZipFile(file) as z:
     for name in z.namelist():assert (dest/name).resolve().is_relative_to(dest.resolve())
     z.extractall(dest)
   graphs=list(dest.rglob('*.onnx'))
  assert len(graphs)==1
  opts=ort.SessionOptions();opts.intra_op_num_threads=2;opts.inter_op_num_threads=1
  sessions[part]=ort.InferenceSession(str(graphs[0]),sess_options=opts,providers=['CPUExecutionProvider']);paths[part]=str(graphs[0])
  print('GRAPH',part,[(x.name,x.shape,x.type) for x in sessions[part].get_inputs()],[(x.name,x.shape) for x in sessions[part].get_outputs()],flush=True)
 # Download is independent of preparing real decoder probes; record inventory first.
 (a.out/'downloaded.json').write_text(json.dumps(dict(paths=paths,meta=meta),indent=2)+'\n')
 from transformers import WhisperFeatureExtractor
 extractor=WhisperFeatureExtractor.from_pretrained(str(a.models/'speech_to_text/phowhisper-base-onnx'),local_files_only=True)
 import wave
 files=sorted(Path('/home/opc/data/btc/dataset/vi-en').glob('*.wav'))[:10]
 allrows=[]
 for i,file in enumerate(files):
  with wave.open(str(file),'rb') as w:
   assert w.getframerate()==16000 and w.getnchannels()==1
   pcm=np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').astype(np.float32)/32768
  mel=extractor(pcm,sampling_rate=16000,return_tensors='np')['input_features'].astype(np.float32)
  split='calibration' if i<8 else 'evaluation';dst=a.out/'inputs'/'phowhisper-base'/'encoder'/split;dst.mkdir(parents=True,exist_ok=True)
  np.savez_compressed(dst/f'{i:03d}.npz',input_features=mel)
  outputs=sessions['encoder'].run(None,{'input_features':mel});cross={n:v for n,v in zip(meta['encoder']['output_names'],outputs)}
  shapes=meta['decoder']['shapes'];state={}
  for n,spec in shapes.items():
   shape,dtype=spec
   if n in cross:state[n]=cross[n]
   elif n=='input_ids':state[n]=np.array([[50258]],dtype=np.int32)
   elif n=='position_ids':state[n]=np.array([0],dtype=np.int32)
   elif n=='attention_mask':state[n]=np.full(shape,-100.,dtype=np.float32)
   else:state[n]=np.zeros(shape,dtype=dtype)
  # Prompt sequence matches current VI transcribe: start, Vietnamese, transcribe, no-timestamps.
  forced=[50258,50278,50359,50363]
  for step in range(9):
   state['position_ids']=np.array([step],dtype=np.int32)
   state['attention_mask']=np.full(shapes['attention_mask'][0],-100.,dtype=np.float32)
   state['attention_mask'][...,-(step+1):]=0.
   if step<len(forced):state['input_ids']=np.array([[forced[step]]],dtype=np.int32)
   if step in [3,8]:
    dst=a.out/'inputs/phowhisper-base/decoder'/split;dst.mkdir(parents=True,exist_ok=True);np.savez_compressed(dst/f'{i:03d}-{step}.npz',**state)
   result=sessions['decoder'].run(None,state);named={n:v for n,v in zip(meta['decoder']['output_names'],result)}
   state['input_ids']=np.array([[int(named['logits'].reshape(-1).argmax())]],dtype=np.int32)
   for n in list(state):
    if n.startswith(('k_cache_self_','v_cache_self_')):
     output=n.removesuffix('_in')+'_out';v=named[output]
     axis=3 if n.startswith('k_') else 2
     previous=state[n]
     # Existing QAI Whisper returns one new cache entry; keep the same static199 history contract.
     if v.shape[axis]==previous.shape[axis]+1:
      slices=[slice(None)]*v.ndim;slices[axis]=slice(1,None);state[n]=v[tuple(slices)]
     elif v.shape[axis]==1:
      slices=[slice(None)]*previous.ndim;slices[axis]=slice(1,None);state[n]=np.concatenate([previous[tuple(slices)],v],axis=axis)
     elif v.shape==previous.shape:state[n]=v
     else:raise ValueError(('Unexpected cache output',n,v.shape,previous.shape))
  allrows.append(dict(wav=str(file),split=split,sample_rate=16000,prompt_tokens=forced))
 (a.out/'input-manifest.json').write_text(json.dumps(allrows,indent=2)+'\n')
 print('PHO_INPUTS_READY',flush=True)
if __name__=='__main__':main()
