#!/usr/bin/env python3
"""Save completed deployable model candidates on VPS with SHA/size/runtime metadata."""
import argparse,hashlib,json,sys,time
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--passes',type=int,default=12);a=p.parse_args()
 import qai_hub as hub
 from qai_hub.client import ClientConfig
 client=hub.Client(ClientConfig(**json.loads(sys.stdin.readline())))
 a.out.mkdir(parents=True,exist_ok=True);file=a.out/'artifacts.json';saved=json.loads(file.read_text()) if file.exists() else {}
 for iteration in range(a.passes):
  candidates={}
  baseline=json.loads((a.root/'aihub-mt-20261113/jobs.json').read_text())
  for k,v in baseline.items():candidates[k+'/baseline']=v['job_id']
  candidates.update({'phowhisper-base/encoder/baseline':'jp0j2300g','phowhisper-base/decoder/baseline':'jp8xm0yqg'})
  for folder in ['aihub-all-tests','aihub-aux-tests','aihub-aux-repaired-tests','aihub-pho-tests','aihub-w8a8-tests','aihub-mask-tests']:
   f=a.root/folder/'tests.json'
   if f.exists():
    for k,v in json.loads(f.read_text()).items():
     if '/compile-' in k and v.get('state')=='success':candidates[k]=v['job_id']
  f=a.root/'aihub-links/links.json'
  if f.exists():
   for k,v in json.loads(f.read_text()).items():
    if v.get('state')=='success' and '/profile/' not in k:candidates['linked/'+k]=v['job_id']
  for k,jid in candidates.items():
   if k in saved:continue
   job=client.get_job(jid)
   if not job.get_status().success:continue
   model=job.get_target_model();dest=a.out/k;dest.parent.mkdir(parents=True,exist_ok=True)
   path=Path(model.download(str(dest)));sha=hashlib.sha256()
   with path.open('rb') as f:
    while block:=f.read(1024*1024):sha.update(block)
   saved[k]=dict(job_id=jid,model_id=model.model_id,path=str(path),bytes=path.stat().st_size,sha256=sha.hexdigest(),
    model_type=str(model.model_type),metadata={str(x):v for x,v in model.metadata.items()},android_ready=False)
   tmp=file.with_suffix('.tmp');tmp.write_text(json.dumps(saved,indent=2)+'\n');tmp.replace(file)
   print('DOWNLOADED',k,path.stat().st_size,flush=True)
  if iteration+1<a.passes:time.sleep(45)
 print('ARTIFACT_PASSES_FINISHED',len(saved),flush=True)
if __name__=='__main__':main()
