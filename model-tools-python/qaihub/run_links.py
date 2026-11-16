#!/usr/bin/env python3
"""Try sharing decoder weights in a linked context, then profile each selected graph."""
import argparse,json,sys,time
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--passes',type=int,default=30);a=p.parse_args()
 import qai_hub as hub
 from qai_hub.client import ClientConfig
 client=hub.Client(ClientConfig(**json.loads(sys.stdin.readline())))
 a.out.mkdir(parents=True,exist_ok=True);file=a.out/'links.json';rows=json.loads(file.read_text()) if file.exists() else {}
 def save():
  tmp=file.with_suffix('.tmp');tmp.write_text(json.dumps(rows,indent=2)+'\n');tmp.replace(file)
 base=json.loads((a.root/'aihub-mt-20261113/jobs.json').read_text())
 base.update({'phowhisper-base/encoder':dict(job_id='jp0j2300g'),'phowhisper-base/decoder':dict(job_id='jp8xm0yqg')})
 groups={name:[k for k in base if k.startswith(name+'/')] for name in ['envit5','vinai-vi2en','vinai-en2vi','phowhisper-base']}
 for iteration in range(a.passes):
  quant={}
  for folder in ['aihub-all-tests','aihub-pho-tests']:
   path=a.root/folder/'tests.json'
   if path.exists():quant.update(json.loads(path.read_text()))
  for name,parts in groups.items():
   for variant in ['baseline','w8a16']:
    key=name+'/'+variant
    if key in rows:continue
    models=[];mapping={}
    for part in parts:
     if variant=='baseline':job=client.get_job(base[part]['job_id'])
     else:
      row=quant.get(part+'/compile-w8a16',{})
      if row.get('state')!='success':break
      job=client.get_job(row['job_id'])
     if not job.get_status().success:break
     model=job.get_target_model();models.append(model)
     names=[str(v) for k,v in model.metadata.items() if 'GRAPH_NAMES' in str(k)]
     mapping[part]=names
    if len(models)!=len(parts):continue
    rows[key]=dict(state='submitting',graphs=mapping);save()
    try:
     job=client.submit_link_job(models,device=hub.Device('Samsung Galaxy S24 Ultra'),name='1Voice '+key+' weight-sharing link')
     rows[key].update(state='submitted',job_id=job.job_id,url=job.url);save();print('LINK',key,job.job_id,flush=True)
    except Exception as e:rows[key].update(state='submission_error',error=str(e));save()
  for key,row in list(rows.items()):
   if row.get('state')=='submitted':
    job=client.get_job(row['job_id']);s=job.get_status()
    if s.finished:
     row.update(state='success' if s.success else 'failed',message=str(s))
     if s.success and '/profile/' not in key:row['model_id']=job.get_target_model().model_id
     save()
   if row.get('state')!='success' or '/profile/' in key:continue
   model=client.get_model(row['model_id'])
   for part,names in row.get('graphs',{}).items():
    pkey=key+'/profile/'+part
    if pkey in rows or len(names)!=1:continue
    rows[pkey]=dict(state='submitting');save()
    try:
     j=client.submit_profile_job(model,device=hub.Device('Samsung Galaxy S24 Ultra'),
       options='--compute_unit npu --qnn_options context_enable_graphs='+names[0],name='1Voice '+pkey,retry=False)
     rows[pkey]=dict(state='submitted',job_id=j.job_id,url=j.url);save();print('LINK_PROFILE',pkey,j.job_id,flush=True)
    except Exception as e:rows[pkey].update(state='submission_error',error=str(e));save()
  if iteration+1<a.passes:time.sleep(45)
 print('LINK_PASSES_FINISHED',flush=True)
if __name__=='__main__':main()
