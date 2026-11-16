#!/usr/bin/env python3
"""Submit independent follow-ups immediately; ledger prevents duplicate/ambiguous retries."""
import argparse,json
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--jobs',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
 import qai_hub as hub
 a.out.mkdir(parents=True,exist_ok=True);file=a.out/'followups.json'
 rows=json.loads(file.read_text()) if file.exists() else {}
 def save():
  tmp=file.with_suffix('.tmp');tmp.write_text(json.dumps(rows,indent=2)+'\n');tmp.replace(file)
 candidates={k:v['job_id'] for k,v in json.loads(a.jobs.read_text()).items()}
 candidates.update({'phowhisper-base/encoder':'jp0j2300g','phowhisper-base/decoder':'jp8xm0yqg'})
 for name,jid in candidates.items():
  original=hub.get_job(jid);s=original.get_status()
  if not s.success:continue
  target=original.get_target_model()
  for compute in ['npu']:
   key=name+'/profile-'+compute
   if key in rows:continue
   rows[key]={'status':'submitting'};save()
   job=hub.submit_profile_job(target,device=hub.Device('Samsung Galaxy S24 Ultra'),
     name='1Voice '+key+' 20261113',options='--compute_unit '+compute,retry=False)
   rows[key]=dict(job_id=job.job_id,url=job.url,status='submitted',source_compile=jid,target_model_id=target.model_id);save()
   print('SUBMITTED',key,job.job_id,flush=True)
  if name.startswith('phowhisper'):continue
  key=name+'/optimize-onnx'
  if key in rows:continue
  rows[key]={'status':'submitting'};save()
  job=hub.submit_compile_job(original.model,device=hub.Device('Samsung Galaxy S24 Ultra'),
    name='1Voice '+key+' 20261113',options='--target_runtime onnx',retry=False)
  rows[key]=dict(job_id=job.job_id,url=job.url,status='submitted',source_model_id=original.model.model_id);save()
  print('SUBMITTED',key,job.job_id,flush=True)
 print('ALL_FOLLOWUPS_SUBMITTED',flush=True)
if __name__=='__main__':main()
