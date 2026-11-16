#!/usr/bin/env python3
"""Collect finished AI Hub profiles without waiting for pending jobs."""
import argparse,collections,json,statistics
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--ledger',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
 import qai_hub as hub
 rows=json.loads(a.ledger.read_text());a.out.mkdir(parents=True,exist_ok=True);summary={}
 for key,row in rows.items():
  if 'profile' not in key or not row.get('job_id'):continue
  j=hub.get_job(row['job_id']);s=j.get_status();r=dict(job_id=j.job_id,status=str(s),success=s.success)
  if s.success:
   file=a.out/(key.replace('/','--')+'.json')
   if file.exists():profile=json.loads(file.read_text())
   else:profile=j.download_profile();file.write_text(json.dumps(profile,indent=2))
   x=profile['execution_summary'];times=x.get('all_inference_times',[])
   r.update(n=len(times),p50_ms=statistics.median(times)/1000 if times else None,
     p95_ms=sorted(times)[min(len(times)-1,int(len(times)*.95))]/1000 if times else None,
     estimated_peak_mib=x['estimated_inference_peak_memory']/1048576,
     compute_units=dict(collections.Counter(v['compute_unit'] for v in profile.get('execution_detail',[]))))
  elif s.finished:
   folder=a.out/(key.replace('/','--')+'-logs');folder.mkdir(exist_ok=True)
   try:j.download_job_logs(str(folder))
   except Exception as e:r['log_error']=str(e)
  summary[key]=r
 (a.out/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
 print(json.dumps(summary,indent=2))
if __name__=='__main__':main()
