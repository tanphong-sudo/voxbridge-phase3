#!/usr/bin/env python3
"""Submit only full-sequence-validated static MT graphs; calibration follows real beam2 decoding."""
import argparse,json,sys,time
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--passes',type=int,default=40);a=p.parse_args()
 import numpy as np,torch,onnxruntime as ort,qai_hub as hub
 from qai_hub.client import ClientConfig
 from transformers import AutoTokenizer,AutoConfig,GenerationConfig
 from ort_static_mt import OrtStaticMt
 from static_mt import SOURCE_LENGTH
 client=hub.Client(ClientConfig(**json.loads(sys.stdin.readline())));root=a.root;out=root/'mt-static-v2';inputs=root/'mt-static-v2-inputs';jobfile=out/'jobs.json'
 jobs=json.loads(jobfile.read_text()) if jobfile.exists() else {}
 data=[json.loads(x) for x in (root/'data/punctuation-btc200.jsonl').read_text().splitlines()]
 torch.set_num_threads(3)
 def save():
  tmp=jobfile.with_suffix('.tmp');tmp.write_text(json.dumps(jobs,indent=2)+'\n');tmp.replace(jobfile)
 class Capture:
  def __init__(self,session,folder):self.session=session;self.folder=folder;self.number=0
  def __getattr__(self,name):return getattr(self.session,name)
  def run(self,names,feeds):
   if self.sample_id is not None and self.position_from(feeds) in [0,3,7,15,31]:
    d=self.folder/self.split;d.mkdir(parents=True,exist_ok=True);np.savez_compressed(d/f'{self.sample_id}-{self.number:03d}.npz',**feeds);self.number+=1
   return self.session.run(names,feeds)
  def position_from(self,feeds):
   if 'position_ids' in feeds:return int(feeds['position_ids'][0,0])
   return int((feeds['self_mask'][0,0,0]==0).sum()-1)
 for attempt in range(a.passes):
  exports=json.loads((out/'exports.json').read_text()) if (out/'exports.json').exists() else {}
  for model in ['vinai-vi2en','vinai-en2vi','envit5']:
   ready=all(exports.get(model+'/'+g,{}).get('full_sequence_parity') for g in ['encoder','decoder'])
   if not ready or all(jobs.get(model+'/'+g,{}).get('quantize_job_id') for g in ['encoder','decoder']):continue
   folder=out/model;kind='envit5' if model=='envit5' else 'vinai';tok=AutoTokenizer.from_pretrained(str(folder/'tokenizer'),local_files_only=True)
   config=AutoConfig.from_pretrained(str(folder/'tokenizer'),local_files_only=True);generation=GenerationConfig.from_pretrained(str(folder/'tokenizer'),local_files_only=True)
   opts=ort.SessionOptions();opts.intra_op_num_threads=3;opts.inter_op_num_threads=1
   enc=ort.InferenceSession(str(folder/'encoder/model.onnx'),sess_options=opts,providers=['CPUExecutionProvider'])
   dec=Capture(ort.InferenceSession(str(folder/'decoder/model.onnx'),sess_options=opts,providers=['CPUExecutionProvider']),inputs/model/'decoder')
   runtime=OrtStaticMt(config,enc,dec,generation);selected=[]
   for lang in (['vi','en'] if kind=='envit5' else ['vi'] if model.endswith('vi2en') else ['en']):
    group=sorted([r for r in data if r['source_lang']==lang],key=lambda r:len(r['source_text']))
    for i,index in enumerate(np.linspace(0,len(group)-1,18,dtype=int)):selected.append((group[index],'evaluation' if i>=16 else 'calibration'))
   if not (inputs/model/'manifest.json').exists():
    records=[]
    for i,(row,split) in enumerate(selected):
     src=row['source_lang']
     if kind=='vinai':tok.src_lang='vi_VN' if src=='vi' else 'en_XX'
     text=src+': '+row['source_text'] if kind=='envit5' else row['source_text']
     e=tok(text,return_tensors='pt',padding='max_length',max_length=SOURCE_LENGTH,truncation=False)
     if e['input_ids'].shape[1]!=SOURCE_LENGTH:raise ValueError('Source too long; no silent truncation')
     f=inputs/model/'encoder'/split;f.mkdir(parents=True,exist_ok=True);np.savez_compressed(f/f'{i:03d}.npz',**{k:v.numpy().astype(np.int32) for k,v in e.items()})
     dec.sample_id=f'{i:03d}';dec.split=split
     kwargs=dict(num_beams=2,max_new_tokens=192,do_sample=False)
     if kind=='vinai':kwargs.update(decoder_start_token_id=tok.lang_code_to_id['en_XX' if src=='vi' else 'vi_VN'],forced_bos_token_id=None,early_stopping=True)
     with torch.inference_mode():seq=runtime.generate(**e,**kwargs)
     records.append(dict(id=row['id'],split=split,source=row['source_text'],target=tok.decode(seq[0],skip_special_tokens=True),tokens=seq[0].tolist()))
    (inputs/model/'manifest.json').write_text(json.dumps(records,ensure_ascii=False,indent=2)+'\n');print('CALIBRATION_READY',model,len(records),flush=True)
   del runtime,enc,dec
   for graph in ['encoder','decoder']:
    key=model+'/'+graph
    if jobs.get(key,{}).get('quantize_job_id'):continue
    if jobs.get(key,{}).get('job_id'):job=client.get_job(jobs[key]['job_id'])
    else:
     jobs[key]=dict(exports[key],status='submitting');save()
     job=client.submit_compile_job(str(folder/graph),device=hub.Device('Samsung Galaxy S24 Ultra'),options='--target_runtime qnn_dlc',name='1Voice FINAL-CANDIDATE-v2 '+key+' src64 tgt192 beam2',retry=False)
     jobs[key].update(status='submitted',job_id=job.job_id,url=job.url);save();print('COMPILE_SUBMITTED',key,job.job_id,flush=True)
    calibration={}
    for sample in sorted((inputs/key/'calibration').glob('*.npz')):
     with np.load(sample) as arrays:
      for n in arrays.files:calibration.setdefault(n,[]).append(arrays[n])
    print('CALIBRATION_LOADED',key,len(next(iter(calibration.values()))),flush=True)
    quant=client.submit_quantize_job(job.model,calibration,
      weights_dtype=hub.QuantizeDtype.INT8,activations_dtype=hub.QuantizeDtype.INT16,name='1Voice FINAL-CANDIDATE-v2 '+key+' W8A16 beam2 actual-cache')
    jobs[key].update(quantize_job_id=quant.job_id,quantize_url=quant.url);save();del calibration;print('QUANTIZE_SUBMITTED',key,quant.job_id,flush=True)
  if len(jobs)==6 and all(r.get('quantize_job_id') for r in jobs.values()):break
  time.sleep(30)
 print('STATIC_MODEL_SUBMISSIONS',len(jobs),flush=True)
if __name__=='__main__':main()
