#!/usr/bin/env python3
"""Separate setup vs speech-path cost; keep cold setup visible and verify same PCM after warm-up."""
import argparse,json,time,wave,hashlib
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--models',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--threads',type=int,default=2);a=p.parse_args()
 import sherpa_onnx,numpy as np
 a.out.mkdir(parents=True,exist_ok=True);rows=[]
 def make(lang):
  d=a.models/'text_to_speech/piper'/('vits-piper-vi_VN-vais1000-medium' if lang=='vi' else 'vits-piper-en_US-lessac-medium');graph,=d.glob('*.onnx')
  return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(vits=sherpa_onnx.OfflineTtsVitsModelConfig(
   model=str(graph),tokens=str(d/'tokens.txt'),data_dir=str(d/'espeak-ng-data'),noise_scale=0.,noise_scale_w=0.,length_scale=1.),num_threads=a.threads,provider='cpu')))
 def render(voice,text):
  audio=voice.generate(text,sid=0,speed=1.)
  pcm=(np.clip(audio.samples,-1,1)*32767).astype('<i2').tobytes()
  return audio.sample_rate,pcm
 for lang,text in [('vi','Dừng máy số năm, không phải máy số ba.'),('en','Stop machine five, not machine three.')]:
  for repetition in range(4):
   start=time.perf_counter();voice=make(lang);sr,before=render(voice,text);cold_ms=(time.perf_counter()-start)*1000;del voice
   start=time.perf_counter();voice=make(lang);render(voice,'Xin chào.' if lang=='vi' else 'Hello.');setup_ms=(time.perf_counter()-start)*1000
   start=time.perf_counter();sr,after=render(voice,text);warm_ms=(time.perf_counter()-start)*1000
   assert before==after,'Warmup changed the rendered output'
   rows.append(dict(language=lang,repetition=repetition,text=text,cold_speech_path_ms=cold_ms,preload_setup_ms=setup_ms,
    prepared_speech_path_ms=warm_ms,pcm_identical=True,sha256=hashlib.sha256(after).hexdigest(),sample_rate=sr))
   del voice
 summary={}
 for lang in ['vi','en']:
  data=[r for r in rows if r['language']==lang];summary[lang]={k:dict(p50=float(np.median([r[k] for r in data])),p95=float(np.percentile([r[k] for r in data],95)))
   for k in ['cold_speech_path_ms','preload_setup_ms','prepared_speech_path_ms']}
 (a.out/'results.json').write_text(json.dumps(dict(rows=rows,summary=summary),indent=2)+'\n');print(json.dumps(summary,indent=2))
if __name__=='__main__':main()
