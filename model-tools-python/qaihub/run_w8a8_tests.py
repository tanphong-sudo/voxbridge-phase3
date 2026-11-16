#!/usr/bin/env python3
"""Resume bounded AI Hub graph tests: real inputs, W8A8, profile, inference comparison.
Credentials enter once on stdin, remain in process memory only. No Android promotion.
"""
import argparse,json,sys,time,traceback
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--inputs',type=Path,required=True);p.add_argument('--exports',type=Path,required=True)
 p.add_argument('--followups',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--passes',type=int,default=1);a=p.parse_args()
 from graph_validation import align_outputs
 import numpy as np,onnxruntime as ort,qai_hub as hub
 from qai_hub.client import ClientConfig
 client=hub.Client(ClientConfig(**json.loads(sys.stdin.readline())))
 a.out.mkdir(parents=True,exist_ok=True);ledger_path=a.out/'tests.json'
 ledger=json.loads(ledger_path.read_text()) if ledger_path.exists() else {}
 original=json.loads((a.exports/'jobs.json').read_text());follow=json.loads(a.followups.read_text())
 def save():
  tmp=ledger_path.with_suffix('.tmp');tmp.write_text(json.dumps(ledger,indent=2)+'\n');tmp.replace(ledger_path)
 def submit(key,operation):
  if key in ledger:return
  ledger[key]={'state':'submitting'};save()
  try:
   job=operation();ledger[key]=dict(state='submitted',job_id=job.job_id,url=job.url);save();print('SUBMITTED',key,job.job_id,flush=True)
  except Exception as e:
   ledger[key].update(state='submission_error',error=type(e).__name__+': '+str(e));save();print('ERROR',key,ledger[key]['error'],flush=True)
 def data(folder,truncate=False):
  rows=[dict(np.load(p)) for p in sorted(folder.glob('*.npz'))]
  if not rows:raise ValueError('No input samples: '+str(folder))
  return {k:[r[k].astype(np.int32) if truncate and r[k].dtype==np.int64 else r[k] for r in rows] for k in rows[0]}
 def refresh():
  for key,row in list(ledger.items()):
   if row.get('state')!='submitted':continue
   try:
    job=client.get_job(row['job_id']);s=job.get_status()
    if not s.finished:continue
    row.update(state='success' if s.success else 'failed',message=str(s))
    if s.success and ('/quantize-' in key or '/compile-' in key):row['model_id']=job.get_target_model().model_id
    if not s.success:
     logs=a.out/'logs'/key;logs.mkdir(parents=True,exist_ok=True)
     try:job.download_job_logs(str(logs))
     except Exception as e:row['log_error']=str(e)
    save()
   except Exception as e:row['read_error']=str(e);save()
 for iteration in range(a.passes):
  refresh()
  for name,meta in original.items():
   folder=a.inputs/name
   if not list((folder/'calibration').glob('*.npz')):continue
   if not meta.get('job_id'):continue
   basejob=client.get_job(meta['job_id'])
   base_status=basejob.get_status()
   if not base_status.success:
    if base_status.finished:ledger.setdefault(name+'/baseline-compile',dict(state='failed',message=str(base_status),job_id=basejob.job_id))
    continue
   base=basejob.get_target_model()
   if name+'/profile-npu' not in follow:
    submit(name+'/profile-baseline',lambda base=base,name=name:client.submit_profile_job(base,device=hub.Device('Samsung Galaxy S24 Ultra'),options='--compute_unit npu',name='1Voice '+name+' baseline NPU profile',retry=False))
   optrow=follow.get(name+'/optimize-onnx',{})
   opt=client.get_job(optrow['job_id']) if optrow.get('job_id') else None
   # Use optimized ONNX if ready; otherwise original validated export remains valid ONNX input.
   model=opt.get_target_model() if opt and opt.get_status().success else basejob.model
   submit(name+'/quantize-w8a8',lambda model=model,folder=folder,name=name:client.submit_quantize_job(
     model,data(folder/'calibration'),weights_dtype=hub.QuantizeDtype.INT8,activations_dtype=hub.QuantizeDtype.INT8,
     name='1Voice '+name+' W8A8 real-BTC calibration'))
   submit(name+'/baseline-inference',lambda base=base,folder=folder,name=name,meta=meta:client.submit_inference_job(
     base,device=hub.Device('Samsung Galaxy S24 Ultra'),inputs=data(folder/'evaluation',meta.get('truncate_64bit_io',False)),
     options='--compute_unit npu',name='1Voice '+name+' baseline real-input inference',retry=False))
   quant=ledger.get(name+'/quantize-w8a8',{})
   if quant.get('state')=='success':
    qm=client.get_model(quant['model_id'])
    submit(name+'/compile-w8a8',lambda qm=qm,name=name,meta=meta:client.submit_compile_job(qm,
      device=hub.Device('Samsung Galaxy S24 Ultra'),options='--target_runtime qnn_dlc'+(' --truncate_64bit_io' if meta.get('truncate_64bit_io') else ''),
      name='1Voice '+name+' W8A8 QNN',retry=False))
   compiled=ledger.get(name+'/compile-w8a8',{})
   if compiled.get('state')=='success':
    cm=client.get_model(compiled['model_id'])
    submit(name+'/profile-w8a8',lambda cm=cm,name=name:client.submit_profile_job(cm,
      device=hub.Device('Samsung Galaxy S24 Ultra'),options='--compute_unit npu',
      name='1Voice '+name+' W8A8 NPU profile',retry=False))
    submit(name+'/inference-w8a8',lambda cm=cm,folder=folder,name=name,meta=meta:client.submit_inference_job(cm,
      device=hub.Device('Samsung Galaxy S24 Ultra'),inputs=data(folder/'evaluation',meta.get('truncate_64bit_io',False)),options='--compute_unit npu',
      name='1Voice '+name+' W8A8 real-input inference',retry=False))
  refresh()
  for name in original:
   folder=a.inputs/name;compiled=ledger.get(name+'/compile-w8a8',{})
   results={}
   for variant,key in [('baseline',name+'/baseline-inference'),('w8a8',name+'/inference-w8a8')]:
    row=ledger.get(key,{})
    if row.get('state')!='success' or row.get('comparison'):continue
    try:
     outputs=client.get_job(row['job_id']).download_output_data()
     opts=ort.SessionOptions();opts.intra_op_num_threads=2;opts.inter_op_num_threads=1
     session=ort.InferenceSession(str(original[name].get('onnx_path',a.exports/name/'model.onnx')),sess_options=opts,providers=['CPUExecutionProvider'])
     names=[x.name for x in session.get_outputs()];reports=[]
     for i,file in enumerate(sorted((folder/'evaluation').glob('*.npz'))):
      feeds=dict(np.load(file));expected=session.run(None,feeds);observed=[]
      observed=[values[i] for values in align_outputs(names,outputs)]
      metrics=[]
      semantic_names=original[name].get('output_names',names)
      for output_name,semantic_name,x,y in zip(names,semantic_names,expected,observed):
       y=np.asarray(y);assert x.shape==y.shape,(output_name,x.shape,y.shape)
       delta=x.astype(np.float64)-y.astype(np.float64);den=np.linalg.norm(x)*np.linalg.norm(y)
       metrics.append(dict(output=output_name,shape=list(x.shape),max_abs=float(np.max(np.abs(delta))),
        rmse=float(np.sqrt(np.mean(delta**2))),cosine=float(np.sum(x*y)/den) if den else None,
        finite=bool(np.isfinite(y).all()),top_token_equal=bool(x.reshape(-1).argmax()==y.reshape(-1).argmax()) if semantic_name=='logits' else None))
      reports.append(dict(file=file.name,outputs=metrics))
     row['comparison']=reports;row.pop('comparison_error',None);save();print('COMPARED',name,variant,flush=True)
     del session
    except Exception as e:row['comparison_error']=type(e).__name__+': '+str(e);save()
  save()
  active=any(r.get('state')=='submitted' for r in ledger.values())
  # Quantization can run for hours; caller controls bounded passes, with 45s between unchanged scans.
  if iteration+1<a.passes and active:time.sleep(45)
  elif not active and iteration>1:break
 print('PASS_FINISHED',json.dumps({s:sum(r.get('state')==s for r in ledger.values()) for s in ['submitted','success','failed','submission_error']}),flush=True)
if __name__=='__main__':main()
