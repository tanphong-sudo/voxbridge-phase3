#!/usr/bin/env python3
"""Download the actual v2 quantized graph pair, compile QNN, run BTC200 before deployment."""
import argparse,json,sys,time,zipfile,subprocess
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--passes',type=int,default=240);a=p.parse_args()
 import qai_hub as hub,onnxruntime as ort
 from qai_hub.client import ClientConfig
 client=hub.Client(ClientConfig(**json.loads(sys.stdin.readline())));root=a.root
 out=root/'mt-static-v2-quantized';out.mkdir(exist_ok=True);inventoryfile=out/'inventory.json';jobsfile=out/'jobs.json'
 inventory=json.loads(inventoryfile.read_text()) if inventoryfile.exists() else {};jobs=json.loads(jobsfile.read_text()) if jobsfile.exists() else {};workers={}
 def save():
  for file,rows in [(inventoryfile,inventory),(jobsfile,jobs)]:
   tmp=file.with_suffix('.tmp');tmp.write_text(json.dumps(rows,indent=2)+'\n');tmp.replace(file)
 for attempt in range(a.passes):
  srcfile=root/'mt-static-v2/jobs.json';rows=json.loads(srcfile.read_text()) if srcfile.exists() else {}
  for key,row in rows.items():
   jid=row.get('quantize_job_id')
   if not jid or key in inventory:continue
   j=client.get_job(jid);status=j.get_status()
   if not status.finished:continue
   if not status.success:inventory[key]=dict(success=False,error=str(status),job_id=jid);save();continue
   model=j.get_target_model();folder=out/key;folder.mkdir(parents=True,exist_ok=True);path=Path(model.download(str(folder/'model')))
   if zipfile.is_zipfile(path):
    with zipfile.ZipFile(path) as z:
     for n in z.namelist():assert (folder/n).resolve().is_relative_to(folder.resolve())
     z.extractall(folder)
    graph,=folder.rglob('*.onnx')
   else:graph=path
   opts=ort.SessionOptions();opts.intra_op_num_threads=2;opts.inter_op_num_threads=1
   session=ort.InferenceSession(str(graph),sess_options=opts,providers=['CPUExecutionProvider']);del session
   inventory[key]=dict(success=True,path=str(graph),model_id=model.model_id,job_id=jid);save();print('QUANTIZED_LOADED',key,flush=True)
   c=client.submit_compile_job(model,device=hub.Device('Samsung Galaxy S24 Ultra'),options='--target_runtime qnn_dlc',name='1Voice full-decode-v2 '+key+' W8A16',retry=False)
   jobs[key]=dict(compile_job_id=c.job_id,url=c.url);save();print('QNN_COMPILE',key,c.job_id,flush=True)
  for name in ['vinai-vi2en','vinai-en2vi','envit5']:
   ready=all(inventory.get(name+'/'+part,{}).get('success') for part in ['encoder','decoder'])
   baseline=root/'mt-static-v2-results/fp32'/name/'summary.json';summary=root/'mt-static-v2-results/w8a16'/name/'summary.json'
   if not ready or not baseline.exists() or summary.exists() or name in workers:continue
   log=(out/(name+'-btc200.log')).open('w')
   proc=subprocess.Popen([sys.executable,str(root/'tools/test_static_btc200.py'),'--root',str(root),'--mode','w8a16','--models',name],stdout=log,stderr=subprocess.STDOUT)
   workers[name]=(proc,log);print('BTC200_STARTED',name,proc.pid,flush=True)
  for name,(proc,log) in list(workers.items()):
   if proc.poll() is not None:
    log.close();jobs['btc200/'+name]=dict(exit_code=proc.returncode,complete=proc.returncode==0);save();del workers[name];print('BTC200_DONE',name,proc.returncode,flush=True)
  for key,row in list(jobs.items()):
   if not row.get('compile_job_id') or row.get('compile_state') in ['success','failed']:continue
   j=client.get_job(row['compile_job_id']);s=j.get_status()
   if s.finished:
    row['compile_state']='success' if s.success else 'failed';row['compile_status']=str(s);save()
    if s.success:
     m=j.get_target_model();dest=out/key/'compiled';path=Path(m.download(str(dest)))
     row.update(dlc_path=str(path),dlc_bytes=path.stat().st_size,model_id=m.model_id);save()
     profile=client.submit_profile_job(m,device=hub.Device('Samsung Galaxy S24 Ultra'),options='--compute_unit npu',name='1Voice full-decode-v2 '+key+' W8A16 NPU',retry=False)
     row['profile_job_id']=profile.job_id;save()
  if all((root/'mt-static-v2-results/w8a16'/name/'summary.json').exists() for name in ['vinai-vi2en','vinai-en2vi','envit5']):break
  time.sleep(45)
 print('STATIC_QUANTIZED_TEST_WORKFLOW_END',flush=True)
if __name__=='__main__':main()
