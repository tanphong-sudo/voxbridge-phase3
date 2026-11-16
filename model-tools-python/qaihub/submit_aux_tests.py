#!/usr/bin/env python3
"""Submit static-shape compile probes for real captured TTS/VAD graph inputs."""
import argparse,json,sys,hashlib
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--inputs',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
 import numpy as np,onnx,onnxruntime as ort,qai_hub as hub
 from qai_hub.client import ClientConfig
 client=hub.Client(ClientConfig(**json.loads(sys.stdin.readline())))
 a.out.mkdir(parents=True,exist_ok=True);ledgerfile=a.out/'jobs.json';followfile=a.out/'followups.json'
 ledger=json.loads(ledgerfile.read_text()) if ledgerfile.exists() else {};follow=json.loads(followfile.read_text()) if followfile.exists() else {}
 def save():
  for file,rows in [(ledgerfile,ledger),(followfile,follow)]:
   tmp=file.with_suffix('.tmp');tmp.write_text(json.dumps(rows,indent=2)+'\n');tmp.replace(file)
 for item in json.loads((a.inputs/'aux-manifest.json').read_text()):
  key=item['key'];dest=a.out/key;dest.mkdir(parents=True,exist_ok=True)
  if ledger.get(key,{}).get('status') in ['submitting','submitted','submission_error']:continue
  sample=dict(np.load(next((a.inputs/key/'calibration').glob('*.npz'))))
  model=onnx.load(item['model'])
  props={}
  for prop in model.metadata_props:
   if prop.key in props and props[prop.key]!=prop.value:raise ValueError('Conflicting model metadata: '+prop.key)
   props[prop.key]=prop.value
  model.ClearField('metadata_props')
  for metadata_key,value in props.items():
   prop=model.metadata_props.add();prop.key=metadata_key;prop.value=value
  for x in model.graph.input:
   if key.startswith('vad/'):continue
   value=sample[x.name];shape=x.type.tensor_type.shape;shape.ClearField('dim')
   for size in value.shape:shape.dim.add().dim_value=int(size)
  path=dest/'model.onnx'
  if (dest/'model.data').exists():(dest/'model.data').unlink()
  onnx.save_model(model,str(path),save_as_external_data=True,all_tensors_to_one_file=True,location='model.data',size_threshold=1024)
  onnx.checker.check_model(str(path));opts=ort.SessionOptions();opts.intra_op_num_threads=2;opts.inter_op_num_threads=1
  session=ort.InferenceSession(str(path),sess_options=opts,providers=['CPUExecutionProvider']);outputs=session.run(None,sample)
  assert outputs and all(np.isfinite(x).all() for x in outputs if np.issubdtype(x.dtype,np.number))
  del model,session
  ledger[key]=dict(status='submitting',model_source=item['model'],input_specs={n:[list(v.shape),str(v.dtype)] for n,v in sample.items()},
    input_provenance=item,onnx_sha256=hashlib.sha256(path.read_bytes()).hexdigest());save()
  try:
   job=client.submit_compile_job(str(dest),device=hub.Device('Samsung Galaxy S24 Ultra'),name='1Voice '+key+' static feasibility',options='--target_runtime qnn_dlc',input_specs={n:(tuple(v.shape),str(v.dtype)) for n,v in sample.items()},retry=False)
   ledger[key].update(status='submitted',job_id=job.job_id,url=job.url);save();print('SUBMITTED',key,job.job_id,flush=True)
   opt=client.submit_compile_job(job.model,device=hub.Device('Samsung Galaxy S24 Ultra'),name='1Voice '+key+' optimized ONNX',options='--target_runtime onnx',retry=False)
   follow[key+'/optimize-onnx']=dict(status='submitted',job_id=opt.job_id,url=opt.url);save()
  except Exception as e:
   ledger[key].update(status='submission_error',error=type(e).__name__+': '+str(e));save();print('ERROR',key,str(e),flush=True)
 print('AUX_ALL_SUBMITTED',flush=True)
if __name__=='__main__':main()
