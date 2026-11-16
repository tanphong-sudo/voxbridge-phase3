#!/usr/bin/env python3
"""Small paired TTS speed/listening comparison. Does not change production models."""
import argparse
import hashlib
import json
import time
import wave
from pathlib import Path

VI=[
 'Dừng máy.', 'Không vào khu vực nguy hiểm.', 'Dừng băng chuyền ngay lập tức.',
 'Không được mở van trước khi tắt máy.', 'Dừng máy số năm, không phải máy số ba.',
 'Áp suất phải dưới ba phẩy năm bar.', 'Điện áp hai trăm hai mươi vôn rất nguy hiểm.',
 'Khoảng cách an toàn là mười lăm mi li mét.', 'Có rò rỉ khí tại khu vực B. Mọi người sơ tán ngay.',
 'Anh đã tắt nguồn điện chưa?', 'Xin vui lòng nói lại chậm hơn.',
 'Kiểm tra dây an toàn trước khi leo lên giàn giáo.'
]
EN=[
 'Stop the machine.', 'Do not enter the hazard zone.', 'Stop the conveyor immediately.',
 'Do not open the valve before turning off the machine.', 'Stop machine five, not machine three.',
 'Pressure must stay below three point five bar.', 'Two hundred twenty volts is dangerous.',
 'The safe distance is fifteen millimeters.', 'There is a gas leak in area B. Evacuate immediately.',
 'Have you turned off the power?', 'Please repeat that more slowly.',
 'Check your safety harness before climbing the scaffolding.'
]

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--models',type=Path,required=True);p.add_argument('--candidates',type=Path,required=True)
 p.add_argument('--out',type=Path,required=True);p.add_argument('--backend',choices=['piper','nano'],required=True)
 p.add_argument('--variants',nargs='*',help='Optional exact variant names for a sequential confirmation run')
 a=p.parse_args()
 import numpy as np
 a.out.mkdir(parents=True,exist_ok=True);results=[];loads={}
 if a.backend=='piper':
  import sherpa_onnx
  variants=[(lang,kind,threads) for lang in ['vi','en'] for kind in ['fp32','int8'] for threads in [2,4]]
  for lang,kind,threads in variants:
   if a.variants and f'piper-{lang}-{kind}-t{threads}' not in a.variants:continue
   bundle='vits-piper-'+('vi_VN-vais1000-medium' if lang=='vi' else 'en_US-lessac-medium')
   folder=(a.models/'text_to_speech/piper'/bundle if kind=='fp32' else a.candidates/(bundle+'-int8'))
   graph,=folder.glob('*.onnx');start=time.perf_counter()
   voice=sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
    vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(graph),tokens=str(folder/'tokens.txt'),data_dir=str(folder/'espeak-ng-data'),
     noise_scale=0.,noise_scale_w=0.,length_scale=1.),num_threads=threads,provider='cpu')))
   name=f'piper-{lang}-{kind}-t{threads}';loads[name]=dict(ms=(time.perf_counter()-start)*1000,graph_bytes=graph.stat().st_size)
   texts=VI if lang=='vi' else EN
   voice.generate(texts[0],sid=0,speed=1.)
   for repeat in range(2):
    for i in (range(len(texts)) if repeat==0 else reversed(range(len(texts)))):
     start=time.perf_counter();audio=voice.generate(texts[i],sid=0,speed=1.);elapsed=(time.perf_counter()-start)*1000
     samples=np.asarray(audio.samples);assert len(samples) and np.isfinite(samples).all()
     pcm=(np.clip(samples,-1,1)*32767).astype('<i2');filename=f'{name}-{i:02d}.wav'
     if repeat==0:
      with wave.open(str(a.out/filename),'wb') as w:
       w.setnchannels(1);w.setsampwidth(2);w.setframerate(audio.sample_rate);w.writeframes(pcm.tobytes())
     results.append(dict(variant=name,language=lang,index=i,text=texts[i],repeat=repeat,ms=elapsed,
      duration_s=len(samples)/audio.sample_rate,wav=filename,sha256=hashlib.sha256(pcm.tobytes()).hexdigest()))
   del voice
 else:
  from vieneu import Vieneu
  d=a.models/'text_to_speech/vieneu-nano'
  for threads in [2,4]:
   if a.variants and not any(f'nano-vi-s{s}-t{threads}' in a.variants for s in [8,4]):continue
   start=time.perf_counter()
   voice=Vieneu(mode='v3nano',backbone_repo=str(d),onnx_dir=str(d),threads=threads,steps=8,hf_token=False)
   load=(time.perf_counter()-start)*1000
   for steps in [8,4]:
    if a.variants and f'nano-vi-s{steps}-t{threads}' not in a.variants:continue
    name=f'nano-vi-s{steps}-t{threads}';loads[name]=dict(ms=load,graph_bytes=sum(x.stat().st_size for x in d.glob('*.onnx')))
    voice.infer(VI[0],voice='Trúc Ly',steps=steps,seed=42,cfg=3.,sway=-1.)
    for repeat in range(2):
     for i in (range(len(VI)) if repeat==0 else reversed(range(len(VI)))):
      start=time.perf_counter();samples=np.asarray(voice.infer(VI[i],voice='Trúc Ly',steps=steps,seed=42,cfg=3.,sway=-1.))
      elapsed=(time.perf_counter()-start)*1000;assert len(samples) and np.isfinite(samples).all()
      pcm=(np.clip(samples,-1,1)*32767).astype('<i2');filename=f'{name}-{i:02d}.wav'
      if repeat==0:
       with wave.open(str(a.out/filename),'wb') as w:
        w.setnchannels(1);w.setsampwidth(2);w.setframerate(24000);w.writeframes(pcm.tobytes())
      results.append(dict(variant=name,language='vi',index=i,text=VI[i],repeat=repeat,ms=elapsed,
       duration_s=len(samples)/24000,wav=filename,sha256=hashlib.sha256(pcm.tobytes()).hexdigest()))
   del voice
 (a.out/(a.backend+'-results.jsonl')).write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in results))
 summary={}
 for name in sorted({r['variant'] for r in results}):
  rows=[r for r in results if r['variant']==name];v=[r['ms'] for r in rows]
  summary[name]=dict(n=len(rows),p50_ms=float(np.median(v)),p95_ms=float(np.percentile(v,95)),
   rtf_p50=float(np.median([r['ms']/1000/r['duration_s'] for r in rows])),**loads[name])
 (a.out/(a.backend+'-summary.json')).write_text(json.dumps(summary,indent=2)+'\n')
 print(json.dumps(summary,indent=2))

if __name__=='__main__':main()
