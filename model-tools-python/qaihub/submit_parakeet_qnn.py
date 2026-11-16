#!/usr/bin/env python3
import argparse,json,sys,time
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--passes',type=int,default=1);a=p.parse_args()
 import qai_hub as hub
 from qai_hub.client import ClientConfig
 client=hub.Client(ClientConfig(**json.loads(sys.stdin.readline())))
 ledger=a.root/'jobs.json';jobs=json.loads(ledger.read_text()) if ledger.exists() else {}
 for name in ['encoder','decoder_joint']:
  if name in jobs and jobs[name].get('state') in ['submitted','success']:continue
  folder=a.root/name
  model=client.upload_model(folder,name='1Voice Parakeet v2 FP32 export '+name)
  opts='--target_runtime qnn_dlc --truncate_64bit_io'
  job=client.submit_compile_job(model,device=hub.Device('Samsung Galaxy S24 Ultra'),options=opts,name='1Voice Parakeet v2 QNN feasibility '+name,retry=False)
  jobs[name]={'state':'submitted','job_id':job.job_id,'url':job.url,'source_model_id':model.model_id,'options':opts,'precision':'FP32 source QNN feasibility only'}
  ledger.write_text(json.dumps(jobs,indent=2)+'\n');print('PARAKEET_COMPILE_SUBMITTED',name,job.job_id,flush=True)
 print(json.dumps(jobs,indent=2))
if __name__=='__main__':main()
