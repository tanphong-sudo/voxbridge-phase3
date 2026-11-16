#!/usr/bin/env python3
"""Download actual AI Hub post-quantization ONNX artifacts and test local loadability."""
import argparse,json,sys,zipfile,hashlib
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
 import qai_hub as hub,onnxruntime as ort
 from qai_hub.client import ClientConfig
 client=hub.Client(ClientConfig(**json.loads(sys.stdin.readline())));a.out.mkdir(parents=True,exist_ok=True)
 inventory={};out=a.out/'inventory.json'
 for group in ['aihub-all-tests','aihub-pho-tests','aihub-aux-tests','aihub-aux-repaired-tests','aihub-w8a8-tests']:
  source=a.root/group/'tests.json'
  if not source.exists():continue
  for key,row in json.loads(source.read_text()).items():
   if '/quantize-' not in key or row.get('state')!='success' or key in inventory:continue
   dest=a.out/key;dest.mkdir(parents=True,exist_ok=True);graphs=list(dest.rglob('*.onnx'))
   item=dict(job_id=row['job_id'],model_id=row['model_id'])
   try:
    if not graphs:
     archive=Path(client.get_model(row['model_id']).download(str(dest/'model')))
     if zipfile.is_zipfile(archive):
      with zipfile.ZipFile(archive) as z:
       for name in z.namelist():assert (dest/name).resolve().is_relative_to(dest.resolve())
       z.extractall(dest)
     graphs=list(dest.rglob('*.onnx'))
    assert len(graphs)==1
    graph=graphs[0];opts=ort.SessionOptions();opts.intra_op_num_threads=2;opts.inter_op_num_threads=1
    session=ort.InferenceSession(str(graph),sess_options=opts,providers=['CPUExecutionProvider'])
    item.update(path=str(graph),onnx_sha256=hashlib.sha256(graph.read_bytes()).hexdigest(),loadable=True,
      inputs=[dict(name=x.name,shape=x.shape,type=x.type) for x in session.get_inputs()],
      outputs=[dict(name=x.name,shape=x.shape,type=x.type) for x in session.get_outputs()]);del session
   except Exception as e:item.update(loadable=False,error=type(e).__name__+': '+str(e))
   inventory[key]=item;tmp=out.with_suffix('.tmp');tmp.write_text(json.dumps(inventory,indent=2)+'\n');tmp.replace(out)
   print('FETCHED',key,item.get('loadable'),item.get('error',''),flush=True)
 print('FETCH_COMPLETE',len(inventory),flush=True)
if __name__=='__main__':main()
