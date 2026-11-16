#!/usr/bin/env python3
"""Run complete translations for every BTC200 source through exact full static graph pair."""
import argparse,gc,json,re,time
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--mode',choices=['fp32','w8a16'],required=True)
 p.add_argument('--models',nargs='*',default=['vinai-vi2en','vinai-en2vi','envit5']);a=p.parse_args()
 import torch,onnxruntime as ort
 from transformers import AutoTokenizer,AutoConfig,GenerationConfig
 from sacrebleu.metrics import CHRF
 from ort_static_mt import OrtStaticMt
 from static_mt import SOURCE_LENGTH
 torch.set_num_threads(3);root=a.root;out=root/'mt-static-v2-results'/a.mode;out.mkdir(parents=True,exist_ok=True)
 dataset=[json.loads(x) for x in (root/'data/punctuation-btc200.jsonl').read_text().splitlines()]
 for model in a.models:
  original=root/'mt-static-v2'/model;folder=out/model;folder.mkdir(exist_ok=True);resultfile=folder/'results.jsonl'
  previous=[json.loads(x) for x in resultfile.read_text().splitlines()] if resultfile.exists() else [];done={r['id'] for r in previous if not r.get('failure')};rows=previous
  kind='envit5' if model=='envit5' else 'vinai';selected=[r for r in dataset if kind=='envit5' or r['source_lang']==('vi' if model.endswith('vi2en') else 'en')]
  paths=[original/g/'model.onnx' for g in ['encoder','decoder']]
  if a.mode=='w8a16':
   quant=json.loads((root/'mt-static-v2-quantized/inventory.json').read_text());paths=[Path(quant[model+'/'+g]['path']) for g in ['encoder','decoder']]
  opts=ort.SessionOptions();opts.intra_op_num_threads=3;opts.inter_op_num_threads=1
  encoder,decoder=[ort.InferenceSession(str(path),sess_options=opts,providers=['CPUExecutionProvider']) for path in paths]
  tok=AutoTokenizer.from_pretrained(str(original/'tokenizer'),local_files_only=True);config=AutoConfig.from_pretrained(str(original/'tokenizer'),local_files_only=True)
  runtime=OrtStaticMt(config,encoder,decoder,GenerationConfig.from_pretrained(str(original/'tokenizer'),local_files_only=True))
  with resultfile.open('a') as log:
   for index,row in enumerate(selected):
    if row['id'] in done:continue
    started=time.perf_counter();entry=dict(id=row['id'],model=model,mode=a.mode,source_lang=row['source_lang'],target_lang=row['target_lang'],source=row['source_text'],reference=row['reference_target'])
    try:
     src=row['source_lang']
     if kind=='vinai':tok.src_lang='vi_VN' if src=='vi' else 'en_XX'
     text=src+': '+row['source_text'] if kind=='envit5' else row['source_text'];encoded=tok(text,return_tensors='pt',padding='max_length',max_length=SOURCE_LENGTH,truncation=False)
     if encoded['input_ids'].shape[1]!=SOURCE_LENGTH:raise ValueError('Source exceeds64token contract; not truncated')
     kwargs=dict(num_beams=2,max_new_tokens=192,do_sample=False)
     if kind=='vinai':kwargs.update(decoder_start_token_id=tok.lang_code_to_id['en_XX' if src=='vi' else 'vi_VN'],forced_bos_token_id=None,early_stopping=True)
     with torch.inference_mode():seq=runtime.generate(**encoded,**kwargs)
     hyp=tok.decode(seq[0],skip_special_tokens=True).strip()
     if kind=='envit5':hyp=re.sub(r'^\s*(?:en|vi)\s*:\s*','',hyp,count=1,flags=re.I).strip()
     entry.update(hypothesis=hyp,tokens=seq[0].tolist(),token_cap=seq.shape[1]-1>=192,failure=None)
    except Exception as e:entry.update(hypothesis='',failure=type(e).__name__+': '+str(e))
    entry['elapsed_ms']=(time.perf_counter()-started)*1000;rows.append(entry);log.write(json.dumps(entry,ensure_ascii=False)+'\n');log.flush()
    if (index+1)%10==0:print('PROGRESS',a.mode,model,index+1,'/',len(selected),flush=True)
  byid={r['id']:r for r in rows};rows=[byid[r['id']] for r in selected]
  summary=dict(n=len(rows),failures=sum(bool(r.get('failure')) for r in rows),blank=sum(not r['hypothesis'] for r in rows),token_cap=sum(r.get('token_cap',False) for r in rows),
    chrf=CHRF().corpus_score([r['hypothesis'] for r in rows],[[r['reference'] for r in rows]]).score,
    scope='complete CPU ONNX beam2 translation, actual artifact; not device latency; AI-draft references')
  if a.mode=='w8a16':
   baseline={r['id']:r for r in [json.loads(x) for x in (root/'mt-static-v2-results/fp32'/model/'results.jsonl').read_text().splitlines()]}
   summary['identical_tokens']=sum(r.get('tokens')==baseline[r['id']].get('tokens') for r in rows);summary['identical_text']=sum(r['hypothesis']==baseline[r['id']]['hypothesis'] for r in rows)
  (folder/'summary.json').write_text(json.dumps(summary,indent=2)+'\n');print('COMPLETE',model,summary,flush=True)
  del runtime,encoder,decoder;gc.collect()
if __name__=='__main__':main()
