import json,sys
from pathlib import Path
import numpy as np,onnxruntime as ort,qai_hub as h
from qai_hub.client import ClientConfig
r=Path('/home/opc/voxbridge-mt3-20261113');c=h.Client(ClientConfig(**json.loads(sys.stdin.readline())));jobs=json.loads((r/'pipeline-phone-contexts/parakeet-cloud-tests.json').read_text());report={}
for part,row in jobs.items():
 inf=c.get_job(row['inference']);pro=c.get_job(row['profile']);entry={'inference_status':str(inf.get_status()),'profile_status':str(pro.get_status())}
 if inf.get_status().success:
  actual=inf.download_output_data();feeds=dict(np.load(r/'pipeline-phone-contexts'/('parakeet-'+part+'-feeds.npz')))
  if part=='encoder':feeds['length']=feeds['length'].astype(np.int64)
  opt=ort.SessionOptions();opt.intra_op_num_threads=3;s=ort.InferenceSession(str(r/'parakeet-v2-static-step-20261114'/part/'model.onnx'),sess_options=opt,providers=['CPUExecutionProvider']);expected=s.run(None,feeds);quality=[]
  for index,x in enumerate(expected):
   value=np.asarray(actual['output_'+str(index)][0]).reshape(x.shape);delta=value.astype(np.float64)-x;quality.append({'source_name':s.get_outputs()[index].name,'finite':bool(np.isfinite(value).all()),'max_abs':float(np.abs(delta).max()),'rmse':float(np.sqrt(np.mean(delta**2)))})
  entry['quality']=quality
  if part=='decoder_joint':entry['first_token_matches']=int(np.argmax(actual['output_0'][0].reshape(-1)[:1025]))==int(np.argmax(expected[0].reshape(-1)[:1025]))
 if pro.get_status().success:
  profile=pro.download_profile();(r/'pipeline-phone-contexts'/('parakeet-'+part+'-profile.json')).write_text(json.dumps(profile,indent=2));times=profile['execution_summary']['all_inference_times'];entry['graph_ms']={'p50':float(np.median(times))/1000,'p95':float(np.percentile(times,95))/1000}
 report[part]=entry
(r/'pipeline-phone-contexts/parakeet-cloud-summary.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
