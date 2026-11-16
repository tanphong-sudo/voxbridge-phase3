#!/usr/bin/env python3
"""Paired Piper CPU thread/sentence-batch experiment; identical text/model/voice settings."""
import argparse,json,time,random
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--models',type=Path,required=True);p.add_argument('--plan',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
 import numpy as np,sherpa_onnx
 rows=[json.loads(x) for x in a.plan.read_text().splitlines()];results=[]
 for lang in ['en','vi']:
  data=[r for r in rows if r['target_lang']==lang]
  selected=sorted(data,key=lambda r:len(r['chunks'][0]))
  indices=np.linspace(0,len(selected)-1,24,dtype=int);selected=[selected[i] for i in indices]
  d=a.models/'text_to_speech/piper'/('vits-piper-en_US-lessac-medium' if lang=='en' else 'vits-piper-vi_VN-vais1000-medium');graph,=d.glob('*.onnx')
  engines={}
  for threads in [1,2,3]:
   for batch in [1,4]:
    cfg=sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(vits=sherpa_onnx.OfflineTtsVitsModelConfig(
     model=str(graph),tokens=str(d/'tokens.txt'),data_dir=str(d/'espeak-ng-data'),noise_scale=0.,noise_scale_w=0.,length_scale=1.),num_threads=threads,provider='cpu'),max_num_sentences=batch)
    engine=sherpa_onnx.OfflineTts(cfg);engine.generate('Hello.' if lang=='en' else 'Xin chào.',sid=0,speed=1.)
    engines[(threads,batch)]=engine
  rng=random.Random(42)
  for repeat in range(2):
   for r in selected:
    configs=list(engines);rng.shuffle(configs)
    for threads,batch in configs:
     text=r['chunks'][0];start=time.perf_counter();audio=engines[(threads,batch)].generate(text,sid=0,speed=1.)
     elapsed=(time.perf_counter()-start)*1000;assert len(audio.samples) and np.isfinite(audio.samples).all()
     results.append(dict(language=lang,id=r['id'],model=r['model'],repeat=repeat,threads=threads,batch=batch,ms=elapsed,samples=len(audio.samples)))
  del engines
 a.out.mkdir(parents=True,exist_ok=True);(a.out/'results.jsonl').write_text(''.join(json.dumps(x)+'\n' for x in results));summary={}
 for lang in ['en','vi']:
  for threads in [1,2,3]:
   for batch in [1,4]:
    v=[x['ms'] for x in results if x['language']==lang and x['threads']==threads and x['batch']==batch]
    summary[f'{lang}-t{threads}-batch{batch}']=dict(n=len(v),p50_ms=float(np.median(v)),p95_ms=float(np.percentile(v,95)))
 (a.out/'summary.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary,indent=2))
if __name__=='__main__':main()
