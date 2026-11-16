#!/usr/bin/env python3
"""Locate observed encoder mismatch across Torch/original ONNX/optimized ONNX/NPU."""
import argparse,json,sys,zipfile
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);a=p.parse_args()
 import numpy as np,torch,onnxruntime as ort,qai_hub as hub
 from qai_hub.client import ClientConfig
 from transformers import AutoModelForSeq2SeqLM
 from graph_validation import align_outputs
 client=hub.Client(ClientConfig(**json.loads(sys.stdin.readline())));root=a.root
 ledger=json.loads((root/'aihub-mt-20261113/jobs.json').read_text());follow=json.loads((root/'tools/followups.json').read_text())
 test=json.loads((root/'aihub-all-tests/tests.json').read_text());meta=ledger['envit5/encoder']
 folder=root/'hf-cache'/('models--'+meta['repo'].replace('/','--'))/'snapshots'/meta['revision']
 torch.set_num_threads(2);model=AutoModelForSeq2SeqLM.from_pretrained(str(folder),use_safetensors=False,local_files_only=True,attn_implementation='eager').eval()
 d=root/'aihub-envit5-debug';d.mkdir(exist_ok=True)
 candidates={'original':root/'aihub-mt-20261113/envit5/encoder/model.onnx'}
 path=Path(client.get_job(follow['envit5/encoder/optimize-onnx']['job_id']).get_target_model().download(str(d/'optimized')))
 if zipfile.is_zipfile(path):
  with zipfile.ZipFile(path) as z:
   for n in z.namelist():assert (d/n).resolve().is_relative_to(d.resolve())
   z.extractall(d)
  graph,=d.rglob('*.onnx')
 else:graph=path
 candidates['optimized']=graph
 opts=ort.SessionOptions();opts.intra_op_num_threads=2;opts.inter_op_num_threads=1
 sessions={k:ort.InferenceSession(str(p),sess_options=opts,providers=['CPUExecutionProvider']) for k,p in candidates.items()}
 output=client.get_job(test['envit5/encoder/baseline-inference']['job_id']).download_output_data()
 npu=align_outputs(['encoder_hidden_states'],output)[0];reports=[]
 for i,path in enumerate(sorted((root/'aihub-all-inputs/envit5/encoder/evaluation').glob('*.npz'))):
  feeds=dict(np.load(path));mask=feeds['attention_mask'][0].astype(bool)
  with torch.inference_mode():expected=model.get_encoder()(**{k:torch.from_numpy(v) for k,v in feeds.items()},return_dict=False)[0].numpy()
  values={k:s.run(None,feeds)[0] for k,s in sessions.items()};values['npu']=npu[i]
  row=dict(file=path.name)
  for k,v in values.items():
   x=expected[:,mask];y=v[:,mask];row[k]=dict(cosine=float(np.sum(x*y)/(np.linalg.norm(x)*np.linalg.norm(y))),
    max_abs=float(np.max(np.abs(x-y))),norm=float(np.linalg.norm(y)),sample0=y.reshape(-1)[:8].tolist())
  reports.append(row)
 (d/'result.json').write_text(json.dumps(reports,indent=2)+'\n');print(json.dumps(reports,indent=2))
if __name__=='__main__':main()
