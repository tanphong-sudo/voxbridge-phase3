#!/usr/bin/env python3
"""Real Nano graph captures + Piper phoneme inputs + fixed-16k VAD frames; experiment only."""
import argparse,json,shutil
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--models',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
 import numpy as np,onnxruntime as ort
 a.out.mkdir(parents=True,exist_ok=True);manifest=[]
 from vieneu import Vieneu
 d=a.models/'text_to_speech/vieneu-nano'
 engine=Vieneu(mode='v3nano',backbone_repo=str(d),onnx_dir=str(d),threads=2,steps=8,hf_token=False)
 phrases=['Dừng máy số năm, không phải máy số ba.','Áp suất phải dưới ba phẩy năm bar.']
 captured={}
 class Capture:
  def __init__(self,session,graph):self.session=session;self.graph=graph
  def __getattr__(self,name):return getattr(self.session,name)
  def run(self,outputs,feeds,*args,**kwargs):
   shape=tuple((k,tuple(v.shape)) for k,v in feeds.items());key=(self.graph,shape)
   rows=captured.setdefault(key,[])
   if len(rows)<12:rows.append({k:v.copy() for k,v in feeds.items()})
   return self.session.run(outputs,feeds,*args,**kwargs)
 for attr,graph in [('s_text','text_encoder'),('s_dur','duration_predictor'),('s_ve','vector_estimator'),('s_dec','codec_decoder')]:
  setattr(engine.engine,attr,Capture(getattr(engine.engine,attr),graph))
 for text in phrases:
  for seed in [42,43,44]:engine.infer(text,voice='Trúc Ly',steps=8,cfg=3.,sway=-1.,seed=seed)
 for graph in ['text_encoder','duration_predictor','vector_estimator','codec_decoder']:
  groups=[(shape,rows) for (g,shape),rows in captured.items() if g==graph]
  shape,rows=max(groups,key=lambda x:len(x[1]));key='nano/'+graph;dest=a.out/key
  for i,feeds in enumerate(rows):
   split='evaluation' if i==len(rows)-1 else 'calibration';folder=dest/split;folder.mkdir(parents=True,exist_ok=True)
   np.savez_compressed(folder/f'{i:03d}.npz',**feeds)
  manifest.append(dict(key=key,model=str(d/(graph+'.onnx')),samples=len(rows),source='captured actual SDK inference',
   note='one static shape selected; no full TTS quality claim'))
 print('NANO_CAPTURED',flush=True)
 from piper_phonemize import phonemize_espeak
 for lang,texts in [('vi',['Dừng máy.','Không vào khu vực nguy hiểm.','Dừng máy số năm, không phải máy số ba.']),
                    ('en',['Stop the machine.','Do not enter the hazard zone.','Stop machine five, not machine three.'])]:
  bundle='vits-piper-'+('vi_VN-vais1000-medium' if lang=='vi' else 'en_US-lessac-medium');folder=a.models/'text_to_speech/piper'/bundle
  graph,=folder.glob('*.onnx');cfg=json.loads(graph.with_suffix('.onnx.json').read_text());mapping=cfg['phoneme_id_map']
  for i,text in enumerate(texts):
   phonemes=phonemize_espeak(text,cfg['espeak']['voice'])
   tokens=list(mapping['^'])+list(mapping['_'])
   missing=[]
   for ph in [x for sentence in phonemes for x in sentence]:
    if ph not in mapping:missing.append(ph);continue
    tokens+=mapping[ph]+mapping['_']
   tokens+=mapping['$']
   feeds=dict(input=np.array([tokens],dtype=np.int64),input_lengths=np.array([len(tokens)],dtype=np.int64),scales=np.array([0.,1.,0.],dtype=np.float32))
   # Separate shapes are legitimate compile probes, not fake fixed-length padding of speech.
   if i>0:continue
   key='piper-'+lang+'/voice';dst=a.out/key
   for split in ['calibration','evaluation']:
    (dst/split).mkdir(parents=True,exist_ok=True);np.savez_compressed(dst/split/'000.npz',**feeds)
   manifest.append(dict(key=key,model=str(graph),samples=1,source=text,phonemizer='piper-phonemize1.1.0',missing_symbols=missing,
     note='single phrase smoke/profile only; insufficient quantization quality calibration'))
 print('PIPER_PREPARED',flush=True)
 # Real 16k WAV frames from existing BTC corpus; sample-rate is an explicit scalar input.
 import wave
 source=next(Path('/home/opc/data/btc').rglob('*.wav'))
 with wave.open(str(source),'rb') as w:
  assert w.getframerate()==16000 and w.getsampwidth()==2 and w.getnchannels()==1
  signal=np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').astype(np.float32)/32768
 key='vad/silero';model=a.models/'vad/silero_vad.onnx';session=ort.InferenceSession(str(model),providers=['CPUExecutionProvider']);state=np.zeros((2,1,128),dtype=np.float32)
 for i in range(10):
  frame=signal[i*512:(i+1)*512]
  if len(frame)!=512:break
  feeds={'input':frame[None],'state':state,'sr':np.array(16000,dtype=np.int64)}
  folder=a.out/key/('evaluation' if i>=8 else 'calibration');folder.mkdir(parents=True,exist_ok=True);np.savez_compressed(folder/f'{i:03d}.npz',**feeds)
  state=session.run(None,feeds)[1]
 manifest.append(dict(key=key,model=str(model),samples=i+1,source=str(source),note='real speech frames, static16kHz probe'))
 (a.out/'aux-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
 print('ALL_AUX_READY',flush=True)
if __name__=='__main__':main()
