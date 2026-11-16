#!/usr/bin/env python3
"""Paired actual first-chunk renders on all retained BTC200 outputs, two vs three threads."""
import argparse,hashlib,json,random,time
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--models',type=Path,required=True);p.add_argument('--plan',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
 import numpy as np,sherpa_onnx
 a.out.mkdir(parents=True,exist_ok=True);rows=[json.loads(x) for x in a.plan.read_text().splitlines()];results=[]
 for lang in ['en','vi']:
  folder=a.models/'text_to_speech/piper'/('vits-piper-en_US-lessac-medium' if lang=='en' else 'vits-piper-vi_VN-vais1000-medium');graph,=folder.glob('*.onnx');voices={}
  for threads in [2,3]:
   voices[threads]=sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(vits=sherpa_onnx.OfflineTtsVitsModelConfig(
    model=str(graph),tokens=str(folder/'tokens.txt'),data_dir=str(folder/'espeak-ng-data'),noise_scale=0.,noise_scale_w=0.,length_scale=1.),num_threads=threads,provider='cpu'),max_num_sentences=1))
   voices[threads].generate('Hello.' if lang=='en' else 'Xin chào.',sid=0,speed=1.)
  rng=random.Random(42)
  for row in [r for r in rows if r['target_lang']==lang]:
   outputs={};elapsed={};order=[2,3];rng.shuffle(order)
   for threads in order:
    start=time.perf_counter();audio=voices[threads].generate(row['chunks'][0],sid=0,speed=1.);elapsed[threads]=(time.perf_counter()-start)*1000
    outputs[threads]=np.asarray(audio.samples)
   assert outputs[2].shape==outputs[3].shape
   delta=outputs[2]-outputs[3]
   pcm2=(np.clip(outputs[2],-1,1)*32767).astype('<i2');pcm3=(np.clip(outputs[3],-1,1)*32767).astype('<i2')
   results.append(dict(id=row['id'],model=row['model'],language=lang,t2_ms=elapsed[2],t3_ms=elapsed[3],
    samples=len(outputs[2]),pcm_identical=bool(np.array_equal(pcm2,pcm3)),max_abs=float(np.max(np.abs(delta))),rmse=float(np.sqrt(np.mean(delta**2)))))
  del voices
 summary={}
 for model in ['envit5','vinai']:
  for lang in ['en','vi']:
   subset=[r for r in results if r['model']==model and r['language']==lang]
   summary[model+'-'+lang]=dict(n=len(subset),t2_p50_ms=float(np.median([r['t2_ms'] for r in subset])),t2_p95_ms=float(np.percentile([r['t2_ms'] for r in subset],95)),
    t3_p50_ms=float(np.median([r['t3_ms'] for r in subset])),t3_p95_ms=float(np.percentile([r['t3_ms'] for r in subset],95)))
 report=dict(n=len(results),pcm_identical=sum(r['pcm_identical'] for r in results),max_abs=max(r['max_abs'] for r in results),groups=summary)
 (a.out/'results.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in results));(a.out/'summary.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
if __name__=='__main__':main()
