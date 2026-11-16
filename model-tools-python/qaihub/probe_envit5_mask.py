#!/usr/bin/env python3
"""Hypothesis test only: FP16-representable attention-mask sentinel; CPU parity required."""
import argparse,json,os,sys
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);a=p.parse_args()
 import numpy as np,onnx,onnxruntime as ort,qai_hub as hub
 from qai_hub.client import ClientConfig
 client=hub.Client(ClientConfig(**json.loads(sys.stdin.readline())));root=a.root
 source=root/'aihub-mt-20261113/envit5/encoder';dest=root/'aihub-mask-exports/envit5-mask1e4/encoder';dest.mkdir(parents=True,exist_ok=True)
 file=root/'aihub-mask-exports/jobs.json'
 if file.exists():print('Existing diagnostic submission; preserve and reuse');return
 m=onnx.load(str(source/'model.onnx'),load_external_data=False);changed=[]
 for node in m.graph.node:
  if node.op_type!='Constant':continue
  for attr in node.attribute:
   if attr.type==onnx.AttributeProto.TENSOR:
    arr=onnx.numpy_helper.to_array(attr.t)
    if arr.size==1 and arr.dtype==np.float32 and float(arr.reshape(-1)[0]) < -1e30:
     changed.append(dict(node=node.name,before=float(arr.reshape(-1)[0]),after=-10000.))
     attr.t.CopyFrom(onnx.numpy_helper.from_array(np.full_like(arr,-10000.)))
 assert len(changed)==1,changed
 for path in source.glob('*.data'):
  target=dest/path.name
  if not target.exists():os.link(path,target)
 onnx.save(m,str(dest/'model.onnx'));onnx.checker.check_model(str(dest/'model.onnx'))
 opts=ort.SessionOptions();opts.intra_op_num_threads=2;opts.inter_op_num_threads=1
 sessions=[ort.InferenceSession(str(d/'model.onnx'),sess_options=opts,providers=['CPUExecutionProvider']) for d in [source,dest]]
 errors=[]
 files=list((root/'aihub-all-inputs/envit5/encoder/calibration').glob('*.npz'))+list((root/'aihub-all-inputs/envit5/encoder/evaluation').glob('*.npz'))
 for path in files:
  data=dict(np.load(path));x=sessions[0].run(None,data)[0];y=sessions[1].run(None,data)[0]
  np.testing.assert_allclose(x,y,rtol=1e-5,atol=1e-5);errors.append(float(np.max(np.abs(x-y))))
 report=dict(changed=changed,n=len(files),cpu_max_abs=max(errors),purpose='test whether FP16 mask overflow causes QNN discrepancy; not accepted fix')
 (root/'aihub-mask-exports/parity.json').write_text(json.dumps(report,indent=2)+'\n')
 job=client.submit_compile_job(str(dest),device=hub.Device('Samsung Galaxy S24 Ultra'),options='--target_runtime qnn_dlc',name='1Voice EnViT5 encoder mask-1e4 diagnostic',retry=False)
 rows={'envit5-mask1e4/encoder':dict(job_id=job.job_id,url=job.url,status='submitted',onnx_path=str(dest/'model.onnx'),diagnostic=report)}
 file.write_text(json.dumps(rows,indent=2)+'\n');(root/'aihub-mask-exports/followups.json').write_text('{}\n')
 inp=root/'aihub-mask-inputs/envit5-mask1e4/encoder';inp.parent.mkdir(parents=True,exist_ok=True)
 if not inp.exists():inp.symlink_to(root/'aihub-all-inputs/envit5/encoder',target_is_directory=True)
 print('MASK_PROBE_SUBMITTED',job.job_id,report,flush=True)
if __name__=='__main__':main()
