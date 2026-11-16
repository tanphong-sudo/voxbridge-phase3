#!/usr/bin/env python3
"""Persistent local-only MT/Piper worker for the real Kotlin audio pipeline harness."""
import argparse,json,sys,time,wave,hashlib,gc
from pathlib import Path
from mt_pilot import MODELS,strip_envit5_prefix

def decide_generation(kind,tok,target):
 opts=dict(num_beams=2,max_new_tokens=192,do_sample=False)
 if kind=='vinai':opts.update(decoder_start_token_id=tok.lang_code_to_id[{'vi':'vi_VN','en':'en_XX'}[target]],forced_bos_token_id=None,early_stopping=True)
 return opts

def cached_prompt_path(kind,lang):
 if kind not in ['ASK_REPEAT','NO_SPEECH'] or lang not in ['vi','en']:raise ValueError('Unsupported prompt')
 return {'ASK_REPEAT':'ask_repeat','NO_SPEECH':'no_speech'}[kind]+'_'+lang

def pcm16(samples):return [int(max(-1.,min(1.,float(s)))*32767) for s in samples]

def main():
 p=argparse.ArgumentParser();p.add_argument('--model',choices=['envit5','vinai'],required=True);p.add_argument('--cache',type=Path,required=True);p.add_argument('--models',type=Path,required=True);p.add_argument('--out',type=Path,required=True);args=p.parse_args()
 import os
 os.environ['TOKENIZERS_PARALLELISM']='false'
 import torch,numpy as np,sherpa_onnx
 from transformers import AutoTokenizer,AutoModelForSeq2SeqLM
 torch.set_num_threads(2);torch.set_num_interop_threads(1)
 model=tok=voice=None;active_repo=active_voice=None
 args.out.mkdir(parents=True,exist_ok=True)
 print(json.dumps(dict(ready=True,model=args.model,sherpa=sherpa_onnx.__version__)),flush=True)
 for line in sys.stdin:
  started=time.perf_counter()
  try:
   r=json.loads(line);op=r['op']
   if op=='mt':
    src,tgt=r['source_lang'],r['target_lang'];repo,rev=MODELS[args.model][src];load_ms=0
    if repo!=active_repo:
     start=time.perf_counter();model=None;tok=None;gc.collect();snapshot=args.cache/('models--'+repo.replace('/','--'))/'snapshots'/rev
     kwargs={'src_lang':{'vi':'vi_VN','en':'en_XX'}[src]} if args.model=='vinai' else {}
     tok=AutoTokenizer.from_pretrained(str(snapshot),local_files_only=True,**kwargs)
     model=AutoModelForSeq2SeqLM.from_pretrained(str(snapshot),local_files_only=True,use_safetensors=False).eval();active_repo=repo;load_ms=(time.perf_counter()-start)*1000
    text=(src+': '+r['text']) if args.model=='envit5' else r['text'];enc=tok(text,return_tensors='pt',truncation=False)
    with torch.inference_mode():ids=model.generate(**enc,**decide_generation(args.model,tok,tgt))
    hyp=tok.decode(ids[0][1:],skip_special_tokens=True).strip()
    if args.model=='envit5':hyp=strip_envit5_prefix(hyp)
    result=dict(text=hyp,load_ms=load_ms,model_revision=rev,tokens=int(ids.shape[-1]-1),hit_token_cap=int(ids.shape[-1]-1)>=192)
   elif op=='tts':
    lang=r['language'];assert lang in ['vi','en'];name=r['id'];assert name and '/' not in name and '..' not in name
    dest=args.out/(name+'.wav');prompt=r.get('prompt');used_cache=False
    if prompt:
     stem=cached_prompt_path(prompt,lang);base=args.models/'text_to_speech/cache';samples=np.frombuffer((base/(stem+'.pcm')).read_bytes(),dtype='<i2');sr=int((base/(stem+'.sr')).read_text());used_cache=True
    else:
     assert len(r['text'])<=2000
     if active_voice!=lang:
      voice=None;gc.collect();folder=args.models/'text_to_speech/piper'/('vits-piper-vi_VN-vais1000-medium' if lang=='vi' else 'vits-piper-en_US-lessac-medium');graphs=list(folder.glob('*.onnx'));assert len(graphs)==1
      cfg=sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(graphs[0]),tokens=str(folder/'tokens.txt'),data_dir=str(folder/'espeak-ng-data'),noise_scale=0.,noise_scale_w=0.,length_scale=1.),num_threads=2,provider='cpu'))
      voice=sherpa_onnx.OfflineTts(cfg);active_voice=lang
     a=voice.generate(r['text'],sid=0,speed=1.);sr=a.sample_rate;samples=np.asarray(pcm16(a.samples),dtype='<i2')
    if len(samples)==0 or sr<=0:raise ValueError('Empty TTS')
    with wave.open(str(dest),'wb') as w:w.setnchannels(1);w.setsampwidth(2);w.setframerate(sr);w.writeframes(samples.tobytes())
    result=dict(wav=str(dest),sample_rate=sr,samples=len(samples),duration_s=len(samples)/sr,cached_prompt=used_cache,sha256=hashlib.sha256(dest.read_bytes()).hexdigest())
   else:raise ValueError('Unknown operation')
   result.update(ok=True,elapsed_ms=(time.perf_counter()-started)*1000)
  except Exception as e:result=dict(ok=False,error=type(e).__name__+': '+str(e),elapsed_ms=(time.perf_counter()-started)*1000)
  print(json.dumps(result,ensure_ascii=False),flush=True)
if __name__=='__main__':main()
