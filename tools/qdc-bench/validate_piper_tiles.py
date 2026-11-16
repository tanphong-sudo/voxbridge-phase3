"""Compare exact unchanged CPU vocoder with overlapped static64 tiles before QNN use."""
import argparse,json
from pathlib import Path
import numpy as np
import onnxruntime as ort
p=argparse.ArgumentParser();p.add_argument('--root',required=True,type=Path);a=p.parse_args()
results={}
for lang in ['vi','en']:
 root=a.root;fixture=np.load(root/'piper-hybrid-front'/lang/'bridge-proof.npz');latent=fixture['latent'];expected=fixture['expected']
 opts=ort.SessionOptions();opts.intra_op_num_threads=3
 session=ort.InferenceSession(str(root/'piper-qnn-retry-rank4'/lang/'vocoder64/model.onnx'),sess_options=opts,providers=['CPUExecutionProvider']);name=session.get_inputs()[0].name
 outcomes=[]
 for halo in [8,12,16,20,24]:
  core=64-2*halo;length=latent.shape[-1];segments=[]
  for offset in range(0,length,core):
   # Preserve original model's true utterance boundaries; only pad the final partial tile.
   begin=max(0,min(offset-halo,max(0,length-64)));end=min(length,begin+64)
   tile=np.pad(latent[:,:,begin:end],[(0,0),(0,0),(0,64-(end-begin))])
   wave=session.run(None,{name:tile})[0].reshape(-1);local=offset-begin;count=min(core,length-offset)
   segments.append(wave[local*256:(local+count)*256])
  actual=np.concatenate(segments);delta=np.abs(actual-expected)
  outcomes.append(dict(halo=halo,core=core,max_abs=float(delta.max()),mean_abs=float(delta.mean()),passed=bool(np.allclose(actual,expected,rtol=1e-5,atol=1e-5))))
 dynamic=ort.InferenceSession(str(root/'piper-qnn-retry-rank4'/lang/'vocoder-dynamic.onnx'),sess_options=opts,providers=['CPUExecutionProvider'])
 short=[]
 for size in [8,16,32,48,63]:
  data=latent[:,:,:size];reference=dynamic.run(None,{name:data})[0].reshape(-1)
  padded=np.pad(data,[(0,0),(0,0),(0,64-size)])
  actual=session.run(None,{name:padded})[0].reshape(-1)[:size*256]
  delta=np.abs(actual-reference)
  short.append(dict(frames=size,max_abs=float(delta.max()),mean_abs=float(delta.mean())))
 results[lang]={'tiles':outcomes,'short_padding':short}
(a.root/'piper-hybrid-front/tile-parity.json').write_text(json.dumps(results,indent=2));print(json.dumps(results,indent=2))
