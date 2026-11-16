#!/usr/bin/env python3
"""Collect all existing experiment jobs/profiles while cloud work completes; read-only API calls."""
import argparse,collections,json,statistics,sys,time
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--passes',type=int,default=120);a=p.parse_args()
 import qai_hub as hub
 from qai_hub.client import ClientConfig
 client=hub.Client(ClientConfig(**json.loads(sys.stdin.readline())))
 a.out.mkdir(parents=True,exist_ok=True);file=a.out/'summary.json';summary=json.loads(file.read_text()) if file.exists() else {}
 for _ in range(a.passes):
  sources=[a.root/'tools/followups.json']
  for group,filename in [('aihub-all-tests','tests.json'),('aihub-pho-tests','tests.json'),('aihub-aux-tests','tests.json'),
   ('aihub-aux-repaired-tests','tests.json'),('aihub-w8a8-tests','tests.json'),('aihub-links','links.json'),('aihub-mask-tests','tests.json')]:sources.append(a.root/group/filename)
  for source in sources:
   if not source.exists():continue
   for key,row in json.loads(source.read_text()).items():
    jid=row.get('job_id')
    if not jid or summary.get(jid,{}).get('finished'):continue
    try:
     job=client.get_job(jid);s=job.get_status();result=dict(key=key,job_id=jid,url=job.url,finished=s.finished,success=s.success,status=str(s))
     if s.success and 'profile' in key:
      profile=job.download_profile();(a.out/(jid+'-profile.json')).write_text(json.dumps(profile,indent=2)+'\n')
      x=profile['execution_summary'];times=x.get('all_inference_times',[])
      result.update(n=len(times),p50_ms=statistics.median(times)/1000 if times else None,
       p95_ms=sorted(times)[min(len(times)-1,int(len(times)*.95))]/1000 if times else None,
       estimated_peak_mib=x['estimated_inference_peak_memory']/1048576,
       compute_units=dict(collections.Counter(v['compute_unit'] for v in profile.get('execution_detail',[]))))
     if s.finished and not s.success:
      logs=a.out/(jid+'-logs');logs.mkdir(exist_ok=True)
      try:job.download_job_logs(str(logs))
      except Exception as e:result['log_error']=str(e)
     summary[jid]=result
    except Exception as e:summary.setdefault(jid,dict(key=key,job_id=jid)).update(read_error=str(e))
  temp=file.with_suffix('.tmp');temp.write_text(json.dumps(summary,indent=2)+'\n');temp.replace(file)
  print('SNAPSHOT',len(summary),'jobs',sum(r.get('finished',False) for r in summary.values()),'finished',flush=True)
  time.sleep(60)
if __name__=='__main__':main()
