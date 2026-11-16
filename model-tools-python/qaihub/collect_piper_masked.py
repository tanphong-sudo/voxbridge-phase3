import json,sys
from pathlib import Path
import numpy as np,onnxruntime as ort,qai_hub as h
from qai_hub.client import ClientConfig
root=Path('/home/opc/voxbridge-mt3-20261113');c=h.Client(ClientConfig(**json.loads(sys.stdin.readline())));jobs=json.loads((root/'piper-masked64/tests.json').read_text());report={}
for lang,row in jobs.items():
 inf=c.get_job(row['inference']);pro=c.get_job(row['profile']);entry={'inference_status':str(inf.get_status()),'profile_status':str(pro.get_status())}
 if inf.get_status().success:
  values=next(iter(inf.download_output_data().values()));latent=np.load(root/'piper-hybrid-front'/lang/'bridge-proof.npz')['latent'];opts=ort.SessionOptions();opts.intra_op_num_threads=3;s=ort.InferenceSession(str(root/'piper-masked64'/lang/'model.onnx'),sess_options=opts,providers=['CPUExecutionProvider']);name=next(x.name for x in s.get_inputs() if x.name!='valid_frames');results=[]
  for size,actual in zip([1,2,8,16,32,48,63,64],values):
   expected=s.run(None,{name:np.pad(latent[:,:,:size],[(0,0),(0,0),(0,64-size)]),'valid_frames':np.array([size],dtype=np.int32)})[0].reshape(-1)[:size*256];actual=np.asarray(actual).reshape(-1)[:size*256];delta=actual.astype(np.float64)-expected;noise=float(np.mean(delta**2));power=float(np.mean(expected.astype(np.float64)**2));results.append({'frames':size,'finite':bool(np.isfinite(actual).all()),'max_abs':float(np.abs(delta).max()),'rmse':noise**.5,'snr_db':float(10*np.log10(max(power,1e-30)/max(noise,1e-30)))})
  entry['quality']=results
 if pro.get_status().success:
  profile=pro.download_profile();(root/'piper-masked64'/lang/'profile.json').write_text(json.dumps(profile,indent=2));times=profile['execution_summary']['all_inference_times'];entry['tile_ms']={'p50':float(np.median(times))/1000,'p95':float(np.percentile(times,95))/1000}
 report[lang]=entry
(root/'piper-masked64/cloud-summary.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
