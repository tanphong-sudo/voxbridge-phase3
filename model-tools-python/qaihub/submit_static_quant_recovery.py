#!/usr/bin/env python3
"""Reuse uploaded v2 source/data for bounded quantization recovery, no duplicate data upload."""
import argparse,json,sys
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);a=p.parse_args()
 import qai_hub as hub
 from qai_hub.client import ClientConfig
 client=hub.Client(ClientConfig(**json.loads(sys.stdin.readline())));root=a.root
 rows=json.loads((root/'mt-static-v2/jobs.json').read_text());out=root/'mt-static-v2-recovery';out.mkdir(exist_ok=True);file=out/'jobs.json';ledger=json.loads(file.read_text()) if file.exists() else {}
 for name,row in rows.items():
  qid=row.get('quantize_job_id')
  if not qid:continue
  old=client.get_job(qid)
  for variant,options in [('minmax','--range_scheme min_max'),('mixed25','--range_scheme min_max --lite_mp percentage=25;override_qtype=fp16')]:
   key=variant+'/'+name
   if key in ledger:continue
   # Mixed precision first covers VinAI, the user's primary deployment comparison.
   if variant=='mixed25' and name.startswith('envit5'):continue
   ledger[key]=dict(state='submitting');file.write_text(json.dumps(ledger,indent=2)+'\n')
   try:
    job=client.submit_quantize_job(old.model,old.calibration_dataset,weights_dtype=hub.QuantizeDtype.INT8,activations_dtype=hub.QuantizeDtype.INT16,
      options=options,name='1Voice v2 '+name+' '+variant+' quality-recovery')
    ledger[key].update(state='submitted',job_id=job.job_id,url=job.url,options=options,source_model_id=old.model.model_id,dataset_id=old.calibration_dataset.dataset_id)
    print('RECOVERY_SUBMITTED',key,job.job_id,flush=True)
   except Exception as e:ledger[key].update(state='submission_error',error=str(e));print('RECOVERY_ERROR',key,str(e),flush=True)
   file.write_text(json.dumps(ledger,indent=2)+'\n')
if __name__=='__main__':main()
