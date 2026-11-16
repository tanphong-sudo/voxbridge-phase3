#!/usr/bin/env python3
"""Build high-fidelity QNN context wrappers from original, full-decode models. No INT8."""
import argparse,json
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--mt',type=Path,required=True);p.add_argument('--asr',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
 import qai_hub as hub
 rows=json.loads(a.mt.read_text());rows.update({'phowhisper-small/'+k:v for k,v in json.loads(a.asr.read_text()).items()})
 a.out.mkdir(parents=True,exist_ok=True);file=a.out/'phone-context-jobs.json';ledger=json.loads(file.read_text()) if file.exists() else {}
 def save():
  temp=file.with_suffix('.tmp');temp.write_text(json.dumps(ledger,indent=2)+'\n');temp.replace(file)
 for name,row in rows.items():
  if name in ledger:continue
  original=hub.get_job(row['job_id'])
  if not original.get_status().success:continue
  if name.startswith('phowhisper'):outputs=row['outputs']
  elif name.endswith('/encoder'):
   count=len(original.get_target_model().output_spec[None]);outputs=['encoder_hidden_states']+[f'cross_{i}_{k}' for i in range((count-1)//2) for k in ['k','v']]
  else:
   count=len(original.get_target_model().output_spec[None]);outputs=['logits']+[f'present_{i}_{k}' for i in range((count-1)//2) for k in ['k','v']]
  options='--target_runtime precompiled_qnn_onnx --qairt_version 2.50 --qnn_options default_graph_htp_precision=FLOAT16 --output_names '+','.join(outputs)
  ledger[name]=dict(state='submitting',source_compile=row['job_id'],precision='floating QNN/FP16 HTP, no integer PTQ',outputs=outputs,options=options);save()
  try:
   job=hub.submit_compile_job(original.model,device=hub.Device('Samsung Galaxy S24 Ultra'),options=options,name='1Voice PHONE-QNN high-fidelity '+name,retry=False)
   ledger[name].update(state='submitted',job_id=job.job_id,url=job.url);save();print('PHONE_QNN',name,job.job_id,flush=True)
  except Exception as e:ledger[name].update(state='submission_error',error=str(e));save();print('ERROR',name,str(e),flush=True)
 print('PHONE_CONTEXT_SUBMISSIONS',len(ledger),flush=True)
if __name__=='__main__':main()
