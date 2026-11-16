"""Verify masked tiled CPU graph against unchanged Piper on all available BTC outputs."""
import argparse,json,time
from pathlib import Path
import numpy as np,onnxruntime as ort,sherpa_onnx
p=argparse.ArgumentParser();p.add_argument('--root',required=True,type=Path);a=p.parse_args();root=a.root
rows=[]
for candidate in ['envit5','vinai']:
 for row in map(json.loads,(root/'full-audio-qnn-ready-20261114'/candidate/'results.jsonl').read_text().splitlines()):
  if row.get('translation_audio_generated'):rows.append(dict(id=row['id'],candidate=candidate,language=row['target_lang'],text=row['tts_text']))
out=root/'piper-hybrid-corpus';out.mkdir(exist_ok=True)
for lang,voice in [('en','en_US-lessac-medium'),('vi','vi_VN-vais1000-medium')]:
 bundle=Path('/home/opc/voxbridge/models/text_to_speech/piper/vits-piper-'+voice)
 def engine(model):return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(model),tokens=str(bundle/'tokens.txt'),data_dir=str(bundle/'espeak-ng-data'),noise_scale=0.,noise_scale_w=0.,length_scale=1.),num_threads=3),max_num_sentences=-1))
 full=engine(bundle/(voice+'.onnx'));front=engine(root/'piper-hybrid-front'/lang/'frontend.onnx');config=sherpa_onnx.GenerationConfig();config.silence_scale=1.;config.speed=1.;config.sid=0
 opts=ort.SessionOptions();opts.intra_op_num_threads=3;s=ort.InferenceSession(str(root/'piper-masked64'/lang/'model.onnx'),sess_options=opts,providers=['CPUExecutionProvider']);name=next(x.name for x in s.get_inputs() if x.name!='valid_frames')
 log=out/(lang+'.jsonl');done={}
 if log.exists():done={r['candidate']+'/'+r['id']:r for r in map(json.loads,log.read_text().splitlines())}
 with log.open('a') as f:
  for row in [r for r in rows if r['language']==lang]:
   key=row['candidate']+'/'+row['id']
   if key in done:continue
   start=time.monotonic();expected=np.asarray(full.generate(row['text'],config).samples);latent=np.asarray(front.generate(row['text'],config).samples,dtype=np.float32).reshape(1,192,-1);frames=latent.shape[-1];segments=[]
   for offset in range(0,frames,32):
    begin=max(0,min(offset-16,max(0,frames-64)));end=min(frames,begin+64);valid=end-begin
    tile=np.pad(latent[:,:,begin:end],[(0,0),(0,0),(0,64-valid)])
    wave=s.run(None,{name:tile,'valid_frames':np.array([valid],dtype=np.int32)})[0].reshape(-1);local=(offset-begin)*256;segments.append(wave[local:local+min(32,frames-offset)*256])
   actual=np.concatenate(segments);same=actual.shape==expected.shape;delta=np.abs(actual-expected) if same else np.array([float('inf')]);passed=same and bool(np.allclose(actual,expected,rtol=1e-4,atol=1e-5))
   result=dict(**row,frames=frames,samples=len(actual),max_abs=float(delta.max()),mean_abs=float(delta.mean()),passed=passed,cpu_validation_ms=(time.monotonic()-start)*1000)
   f.write(json.dumps(result,ensure_ascii=False)+'\n');f.flush()
   if not passed:raise RuntimeError('Piper hybrid mismatch '+key+': '+str(result))
 print('PIPER_CORPUS_DONE',lang,flush=True)
results=[r for path in out.glob('*.jsonl') for r in map(json.loads,path.read_text().splitlines())];report={'n':len(results),'expected_n':len(rows),'passed':len(results)==len(rows) and all(r['passed'] for r in results),'max_abs':max(r['max_abs'] for r in results),'scope':'CPU FP32 masked static64/tiled vocoder vs original untrimmed Piper; QNN waveform quality and device timing require separate validation'}
(out/'summary.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)
