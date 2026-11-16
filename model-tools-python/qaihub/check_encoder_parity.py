#!/usr/bin/env python3
import argparse,json,sys
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);a=p.parse_args()
 import numpy as np,onnxruntime as ort,qai_hub as hub
 from qai_hub.client import ClientConfig
 from graph_validation import align_outputs
 client=hub.Client(ClientConfig(**json.loads(sys.stdin.readline())))
 root=a.root;tests=json.loads((root/'aihub-all-tests/tests.json').read_text());reports=[]
 for name in ['envit5/encoder','vinai-vi2en/encoder','vinai-en2vi/encoder']:
  inputs=sorted((root/'aihub-all-inputs'/name/'evaluation').glob('*.npz'))
  opts=ort.SessionOptions();opts.intra_op_num_threads=2;opts.inter_op_num_threads=1
  session=ort.InferenceSession(str(root/'aihub-mt-20261113'/name/'model.onnx'),sess_options=opts,providers=['CPUExecutionProvider'])
  for variant in ['baseline-inference','inference-w8a16']:
   row=tests.get(name+'/'+variant,{})
   if row.get('state')!='success':continue
   outputs=client.get_job(row['job_id']).download_output_data();values=align_outputs([v.name for v in session.get_outputs()],outputs)[0]
   for i,file in enumerate(inputs):
    feeds=dict(np.load(file));x=session.run(None,feeds)[0];y=values[i];mask=feeds['attention_mask'][0].astype(bool)
    report=dict(key=name+'/'+variant,file=file.name,mask_tokens=int(mask.sum()),finite=bool(np.isfinite(y).all()))
    for scope,xx,yy in [('all',x,y),('nonpad',x[:,mask],y[:,mask])]:
     report[scope]=dict(cosine=float(np.sum(xx*yy)/(np.linalg.norm(xx)*np.linalg.norm(yy))),
      max_abs=float(np.max(np.abs(xx-yy))),reference_norm=float(np.linalg.norm(xx)),device_norm=float(np.linalg.norm(yy)))
    reports.append(report)
 (root/'aihub-all-tests/encoder-parity-detail.json').write_text(json.dumps(reports,indent=2)+'\n');print(json.dumps(reports,indent=2))
if __name__=='__main__':main()
