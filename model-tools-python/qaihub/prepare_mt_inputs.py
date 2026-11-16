#!/usr/bin/env python3
"""Real BTC source-only graph calibration and held-out graph probe inputs; no reference translations."""
import argparse,gc,json
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--cache',type=Path,required=True);p.add_argument('--exports',type=Path,required=True)
 p.add_argument('--corpus',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
 import torch,numpy as np,onnxruntime as ort
 from transformers import AutoTokenizer,AutoModelForSeq2SeqLM
 torch.set_num_threads(4);torch.set_num_interop_threads(1)
 data=[json.loads(x) for x in (a.corpus/'envit5/results.jsonl').read_text().splitlines()]
 ledger=json.loads((a.exports/'jobs.json').read_text());manifest=[]
 for name in ['envit5','vinai-vi2en','vinai-en2vi']:
  meta=ledger[name+'/encoder'];repo,rev=meta['repo'],meta['revision'];folder=a.cache/('models--'+repo.replace('/','--'))/'snapshots'/rev
  model=AutoModelForSeq2SeqLM.from_pretrained(str(folder),local_files_only=True,use_safetensors=False,attn_implementation='eager').eval()
  tok=AutoTokenizer.from_pretrained(str(folder),local_files_only=True,**({'src_lang':'vi_VN' if name.endswith('vi2en') else 'en_XX'} if name!='envit5' else {}))
  chosen=[]
  for lang in (['vi','en'] if name=='envit5' else ['vi'] if name.endswith('vi2en') else ['en']):
   candidates=sorted([r for r in data if r['source_lang']==lang and len(r.get('raw_asr','').split())>=9],key=lambda r:r['id'])
   selected=candidates[:10]
   assert len(selected)==10
   chosen.extend((r,'calibration' if i<8 else 'evaluation') for i,r in enumerate(selected))
  samples=[]
  for number,(row,split) in enumerate(chosen):
   lang=row['source_lang'];text=(lang+': '+row['raw_asr']) if name=='envit5' else row['raw_asr']
   if name!='envit5':tok.src_lang='vi_VN' if lang=='vi' else 'en_XX'
   encoded=tok(text,return_tensors='pt',padding='max_length',max_length=128,truncation=True)
   ids=encoded['input_ids'].to(torch.int32);mask=encoded['attention_mask'].to(torch.int32)
   start=model.config.decoder_start_token_id if name=='envit5' else tok.lang_code_to_id['en_XX' if lang=='vi' else 'vi_VN']
   with torch.inference_mode():
    hidden=model.get_encoder()(input_ids=ids,attention_mask=mask,return_dict=False)[0]
    seq=model.generate(input_ids=ids,attention_mask=mask,num_beams=1,max_new_tokens=12,
      decoder_start_token_id=start,forced_bos_token_id=None)
    # Always a valid length-eight prefix; EOS padding is recorded rather than hidden.
    prefix=seq[:,:8]
    if prefix.shape[1]<8:prefix=torch.nn.functional.pad(prefix,(0,8-prefix.shape[1]),value=model.config.eos_token_id)
    history=model(encoder_outputs=(hidden,),attention_mask=mask,decoder_input_ids=prefix,use_cache=True,return_dict=True).past_key_values
   dec=torch.tensor([[start]],dtype=torch.int32)
   step=seq[:,8:9].to(torch.int32) if seq.shape[1]>8 else torch.tensor([[model.config.eos_token_id]],dtype=torch.int32)
   samples.append(dict(id=row['id'],split=split,source_lang=lang,source_text=row['raw_asr'],generated_prefix_tokens=prefix.tolist(),eos_padded=seq.shape[1]<9))
   for graph in ['encoder','decoder-init','decoder-step']:
    key=name+'/'+graph;spec=ledger[key]['input_specs']
    feeds={'input_ids':ids.numpy(),'attention_mask':mask.numpy(),'decoder_input_ids':dec.numpy() if graph=='decoder-init' else step.numpy(),
      'encoder_hidden_states':hidden.numpy(),'encoder_attention_mask':mask.numpy()}
    if graph=='decoder-step':
     for i,layer in enumerate(history):
      for kind,t in zip(['self_k','self_v','cross_k','cross_v'],layer):feeds[f'past_{i}_{kind}']=t.numpy()
    feeds={n:feeds[n] for n in spec}
    dst=a.out/name/graph/split;dst.mkdir(parents=True,exist_ok=True);np.savez_compressed(dst/f'{number:03d}.npz',**feeds)
  manifest.append(dict(name=name,repo=repo,revision=rev,samples=samples))
  del model,tok,hidden,history;gc.collect()
  print('PREPARED',name,len(chosen),'sources',flush=True)
 a.out.mkdir(parents=True,exist_ok=True);(a.out/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
 print('ALL_MT_INPUTS_READY',flush=True)
if __name__=='__main__':main()
