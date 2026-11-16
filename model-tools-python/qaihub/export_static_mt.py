#!/usr/bin/env python3
"""Export complete source64/target192/beam2 MT candidates and gate full-sequence parity."""
import argparse,gc,json,time,hashlib
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
 import numpy as np,torch,onnx,onnxruntime as ort
 from transformers import AutoTokenizer,AutoModelForSeq2SeqLM,GenerationConfig
 from static_mt import StaticEncoder,StaticDecoder,empty_cache,SOURCE_LENGTH,TARGET_LENGTH,BEAMS
 from ort_static_mt import OrtStaticMt
 torch.set_num_threads(4);torch.set_num_interop_threads(1)
 a.out.mkdir(parents=True,exist_ok=True);original=json.loads((a.root/'aihub-mt-20261113/jobs.json').read_text());ledger={}
 dataset=[json.loads(x) for x in (a.root/'data/punctuation-btc200.jsonl').read_text().splitlines()]
 for name in ['vinai-vi2en','vinai-en2vi','envit5']:
  kind='envit5' if name=='envit5' else 'vinai';meta=original[name+'/encoder'];folder=a.root/'hf-cache'/('models--'+meta['repo'].replace('/','--'))/'snapshots'/meta['revision']
  selected=[r for r in dataset if name=='envit5' or r['source_lang']==('vi' if name.endswith('vi2en') else 'en')]
  model=AutoModelForSeq2SeqLM.from_pretrained(str(folder),local_files_only=True,use_safetensors=False,attn_implementation='eager').eval()
  tok=AutoTokenizer.from_pretrained(str(folder),local_files_only=True,**({'src_lang':'vi_VN' if selected[0]['source_lang']=='vi' else 'en_XX'} if kind=='vinai' else {}))
  config=model.config.to_dict();d=a.out/name;d.mkdir(exist_ok=True);tok.save_pretrained(d/'tokenizer');model.config.save_pretrained(d/'tokenizer');model.generation_config.save_pretrained(d/'tokenizer')
  lang=selected[0]['source_lang'];text=(lang+': '+selected[0]['source_text']) if kind=='envit5' else selected[0]['source_text']
  enc=tok(text,return_tensors='pt',padding='max_length',max_length=SOURCE_LENGTH,truncation=False);ids=enc['input_ids'].to(torch.int32);mask=enc['attention_mask'].to(torch.int32)
  encoder=StaticEncoder(model,kind).eval();decoder=StaticDecoder(model,kind).eval()
  with torch.inference_mode():cross=encoder(ids,mask)
  start=model.config.decoder_start_token_id if kind=='envit5' else tok.lang_code_to_id['en_XX' if lang=='vi' else 'vi_VN']
  cache=[]
  for i,(k,v) in enumerate(empty_cache(model,kind)):cache.extend([k,v,cross[1+i*2].repeat(BEAMS,1,1,1),cross[2+i*2].repeat(BEAMS,1,1,1)])
  pos=torch.zeros((BEAMS,1),dtype=torch.int32);selfmask=torch.full((BEAMS,1,1,TARGET_LENGTH),-10000.);selfmask[...,-1]=0.
  crossmask=(1-mask[:,None,None,:].float()).repeat(BEAMS,1,1,1)*-10000.
  for graph,wrapper,inputs,names,outputs in [
   ('encoder',encoder,(ids,mask),['input_ids','attention_mask'],['encoder_hidden_states']+[f'cross_{i}_{k}' for i in range(len(cache)//4) for k in ['k','v']]),
   ('decoder',decoder,(torch.full((BEAMS,1),start,dtype=torch.int32),pos,selfmask,crossmask,*cache),
    ['input_ids','position_ids','self_mask','cross_mask']+[f'cache_{i}_{k}' for i in range(len(cache)//4) for k in ['self_k','self_v','cross_k','cross_v']],
    ['logits']+[f'present_{i}_{k}' for i in range(len(cache)//4) for k in ['k','v']])]:
   dest=d/graph;dest.mkdir(exist_ok=True);path=dest/'model.onnx';print('EXPORT',name,graph,flush=True)
   with torch.inference_mode():expected=wrapper(*inputs);torch.onnx.export(wrapper,inputs,str(path),input_names=names,output_names=outputs,opset_version=17,dynamo=False,do_constant_folding=True)
   onnx_model=onnx.load(str(path));changed=[]
   for node in onnx_model.graph.node:
    if node.op_type=='Constant':
     for attr in node.attribute:
      if attr.type==onnx.AttributeProto.TENSOR:
       x=onnx.numpy_helper.to_array(attr.t)
       if x.size==1 and np.issubdtype(x.dtype,np.floating) and float(x.reshape(-1)[0]) < -1e30:
        attr.t.CopyFrom(onnx.numpy_helper.from_array(np.full_like(x,-10000.)));changed.append(node.name)
   data=dest/'model.data'
   if data.exists():data.unlink()
   onnx.save_model(onnx_model,str(path),save_as_external_data=True,all_tensors_to_one_file=True,location='model.data',size_threshold=0);del onnx_model;gc.collect()
   onnx.checker.check_model(str(path));opts=ort.SessionOptions();opts.intra_op_num_threads=3;opts.inter_op_num_threads=1
   session=ort.InferenceSession(str(path),sess_options=opts,providers=['CPUExecutionProvider']);feeds={n:t.numpy() for n,t in zip(names,inputs) if n in {x.name for x in session.get_inputs()}}
   actual=session.run(None,feeds);errors=[]
   for x,y in zip(actual,expected):np.testing.assert_allclose(x,y.numpy(),rtol=5e-3,atol=5e-3);errors.append(float(np.max(np.abs(x-y.numpy()))))
   ledger[name+'/'+graph]=dict(repo=meta['repo'],revision=meta['revision'],model_type=kind,source_tokens=SOURCE_LENGTH,target_tokens=TARGET_LENGTH,beam_batch=BEAMS,
    input_specs={n:[list(t.shape),str(t.dtype).removeprefix('torch.')] for n,t in zip(names,inputs) if n in feeds},changed_mask_nodes=changed,
    parity_max_abs=errors,onnx_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),bytes=sum(f.stat().st_size for f in dest.iterdir()))
   (a.out/'exports.json').write_text(json.dumps(ledger,indent=2)+'\n');del session,actual,expected;gc.collect()
  # Full generated beam2 sequences must match before any cloud submission.
  opts=ort.SessionOptions();opts.intra_op_num_threads=3;opts.inter_op_num_threads=1
  sessions=[ort.InferenceSession(str(d/g/'model.onnx'),sess_options=opts,providers=['CPUExecutionProvider']) for g in ['encoder','decoder']]
  runtime=OrtStaticMt(model.config,*sessions,model.generation_config)
  checks=[]
  for row in sorted(selected,key=lambda r:len(r['source_text']),reverse=True)[:6]:
   src=row['source_lang'];tok.src_lang='vi_VN' if src=='vi' else 'en_XX' if kind=='vinai' else None
   text=(src+': '+row['source_text']) if kind=='envit5' else row['source_text'];e=tok(text,return_tensors='pt',padding='max_length',max_length=SOURCE_LENGTH,truncation=False)
   start=model.config.decoder_start_token_id if kind=='envit5' else tok.lang_code_to_id['en_XX' if src=='vi' else 'vi_VN']
   kwargs=dict(num_beams=2,max_new_tokens=192,do_sample=False)
   if kind=='vinai':kwargs.update(decoder_start_token_id=start,forced_bos_token_id=None,early_stopping=True)
   with torch.inference_mode():reference=model.generate(**e,**kwargs);candidate=runtime.generate(**e,**kwargs)
   exact=reference.tolist()==candidate.tolist();checks.append(dict(id=row['id'],exact_tokens=exact,reference=tok.decode(reference[0],skip_special_tokens=True),candidate=tok.decode(candidate[0],skip_special_tokens=True)))
   print('SEQUENCE',name,row['id'],exact,flush=True)
  (d/'sequence-parity.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2)+'\n')
  if not all(c['exact_tokens'] for c in checks):raise RuntimeError('Full-sequence parity failed: '+name)
  for graph in ['encoder','decoder']:ledger[name+'/'+graph]['full_sequence_parity']=checks
  (a.out/'exports.json').write_text(json.dumps(ledger,ensure_ascii=False,indent=2)+'\n')
  del runtime,model,encoder,decoder,sessions,cross,cache;gc.collect()
 print('STATIC_EXPORTS_READY',flush=True)
if __name__=='__main__':main()
