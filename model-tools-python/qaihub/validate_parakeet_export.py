#!/usr/bin/env python3
"""Numerical FP32 component parity; not full TDT WER or a QNN result."""
import argparse,json,wave,time,gc,sys,os
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--checkpoint',type=Path,required=True);p.add_argument('--manifest',type=Path,required=True);a=p.parse_args()
 import torch,numpy as np,onnx
 # Reuse the existing host-only ORT installation without replacing this pinned NeMo/Torch env.
 if os.environ.get('VOXBRIDGE_ORT_SITE'):sys.path.append(os.environ['VOXBRIDGE_ORT_SITE'])
 import onnxruntime as ort
 torch.set_num_threads(4);torch.set_num_interop_threads(1);torch.manual_seed(0)
 import nemo.collections.asr as na
 from nemo.core.classes.common import typecheck
 model=na.models.ASRModel.restore_from(str(a.checkpoint),map_location='cpu').float().eval();model.preprocessor.featurizer.dither=0.
 # Inference parity must exercise the exported joint logits, not the training WER auxiliary path.
 if hasattr(model.joint, 'set_fuse_loss_wer'):model.joint.set_fuse_loss_wer(False)
 model.joint.log_softmax=False
 model.decoder._rnnt_export=True
 frames=json.loads((a.root/'status.json').read_text())['frames'];report={'scope':'CPU FP32 component numerical parity; not whole ASR WER, not QNN, not phone latency','parts':{}}
 rows=[json.loads(s) for s in a.manifest.read_text().splitlines() if s.strip()]
 for part in ['decoder_joint','encoder']:
  path=a.root/part/'model.onnx';onnx.checker.check_model(str(path))
  opts=ort.SessionOptions();opts.intra_op_num_threads=3;opts.inter_op_num_threads=1
  session=ort.InferenceSession(str(path),sess_options=opts,providers=['CPUExecutionProvider'])
  specs=[{'name':n.name,'shape':n.shape,'type':n.type} for n in session.get_inputs()]
  probes=[]
  for index in range(3):
   if part=='encoder':
    row=rows[index]
    with wave.open(row['wav'],'rb') as w:
     assert w.getnchannels()==1 and w.getframerate()==16000 and w.getsampwidth()==2
     waveform=np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').astype(np.float32)/32768.
    with torch.inference_mode():
     features,length=model.preprocessor(input_signal=torch.from_numpy(waveform)[None],length=torch.tensor([len(waveform)]))
     assert features.shape[-1]<=frames,'No silent audio truncation'
     features=torch.nn.functional.pad(features,(0,frames-features.shape[-1]))
     args=(features,length);expected=model.encoder(audio_signal=features,length=length)
    label=row['id']
   else:
    example=model.decoder_joint.input_example(max_batch=1,max_dim=1)
    args=(example[0][...,:1],*example[1:])
    with torch.inference_mode(), typecheck.disable_checks():expected=model.decoder_joint(*args)
    label='recurrent-probe-'+str(index)
   if not isinstance(expected,(tuple,list)):expected=(expected,)
   if len(args)!=len(specs):raise ValueError('Export input argument mismatch')
   feeds={n['name']:v.detach().cpu().numpy() for n,v in zip(specs,args)}
   actual=session.run(None,feeds);assert len(actual)==len(expected)
   checks=[]
   for x,y in zip(actual,expected):
    y=y.detach().cpu().numpy()
    if x.shape!=y.shape:
     checks.append({'shape_mismatch':True,'onnx_shape':list(x.shape),'source_shape':list(y.shape),'allclose':False});continue
    diff=float(np.max(np.abs(x-y))) if x.size else 0.
    same=bool(np.allclose(x,y,rtol=1e-3,atol=1e-3));checks.append({'max_abs':diff,'allclose':same})
   probes.append({'id':label,'checks':checks});print(part,label,checks,flush=True)
  report['parts'][part]={'inputs':specs,'probes':probes,'passed':all(c['allclose'] for r in probes for c in r['checks'])}
  (a.root/'cpu-component-parity.json').write_text(json.dumps(report,indent=2)+'\n')
  del session;gc.collect()
 report['passed']=all(p['passed'] for p in report['parts'].values())
 (a.root/'cpu-component-parity.json').write_text(json.dumps(report,indent=2)+'\n')
 if not report['passed']:raise SystemExit('CPU component parity failed; do not compile or deploy this candidate')
 print('CPU_COMPONENT_PARITY_PASS',flush=True)
if __name__=='__main__':main()
