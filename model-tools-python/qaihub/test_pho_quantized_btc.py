#!/usr/bin/env python3
"""Full PhoWhisper greedy recognition on100 original VI WAVs, exact AI Hub QDQ vs source ONNX."""
import argparse,json,re,time,unicodedata,wave
from pathlib import Path

def words(text):return re.findall(r'[^\W_]+',unicodedata.normalize('NFKC',text.casefold()),re.UNICODE)
def edits(a,b):
 row=list(range(len(b)+1))
 for i,x in enumerate(a,1):
  nxt=[i]
  for j,y in enumerate(b,1):nxt.append(min(row[j]+1,nxt[-1]+1,row[j-1]+(x!=y)))
  row=nxt
 return row[-1]

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--variant',choices=['w8a16','encoder-w8a16','encoder-w8a8'],default='w8a16');a=p.parse_args()
 import numpy as np,onnxruntime as ort
 from transformers import WhisperFeatureExtractor,AutoTokenizer
 root=a.root;out=root/('phowhisper-quantized-btc100' if a.variant=='w8a16' else 'phowhisper-'+a.variant+'-btc100');out.mkdir(exist_ok=True);resultfile=out/'results.jsonl'
 if resultfile.exists():raise RuntimeError('Preserve existing test outputs')
 original=json.loads((root/'aihub-pho-inputs/downloaded.json').read_text());quant=json.loads((root/'aihub-quantized-onnx/inventory.json').read_text())
 sessions={};opts=ort.SessionOptions();opts.intra_op_num_threads=3;opts.inter_op_num_threads=1
 for mode in ['fp32','w8a16']:
  sessions[mode]={}
  for part in ['encoder','decoder']:
   precision='w8a8' if a.variant=='encoder-w8a8' else 'w8a16'
   path=original['paths'][part] if mode=='fp32' or (part=='decoder' and a.variant.startswith('encoder-')) else quant['phowhisper-base/'+part+'/quantize-'+precision]['path']
   sessions[mode][part]=ort.InferenceSession(str(path),sess_options=opts,providers=['CPUExecutionProvider'])
 cfg='/home/opc/voxbridge/models/speech_to_text/phowhisper-base-onnx'
 extractor=WhisperFeatureExtractor.from_pretrained(cfg,local_files_only=True);tok=AutoTokenizer.from_pretrained(cfg,local_files_only=True)
 corpus=[json.loads(x) for x in (root/'full-audio-btc200/manifest200.jsonl').read_text().splitlines()];corpus=[r for r in corpus if r['source_lang']=='vi'];assert len(corpus)==100
 rows=[]
 with resultfile.open('w') as log:
  for index,row in enumerate(corpus):
   with wave.open(row['wav'],'rb') as w:
    assert w.getframerate()==16000 and w.getnchannels()==1
    pcm=np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').astype(np.float32)/32768
   mel=extractor(pcm,sampling_rate=16000,return_tensors='np')['input_features'].astype(np.float32)
   result=dict(id=row['id'],reference=row['reference_source'],input='original full WAV; no VAD; same audio for both candidates')
   for mode in ['fp32','w8a16']:
    started=time.perf_counter();enc=sessions[mode]['encoder'];dec=sessions[mode]['decoder'];raw=enc.run(None,{'input_features':mel});cross=dict(zip(original['meta']['encoder']['output_names'],raw))
    shapes=original['meta']['decoder']['shapes'];state={}
    for name,(shape,dtype) in shapes.items():state[name]=cross[name] if name in cross else np.zeros(shape,dtype=dtype)
    forced=[50258,50278,50359,50363];tokens=[];stop='token_cap'
    for pos in range(200):
     state['input_ids']=np.array([[forced[pos] if pos<len(forced) else token]],dtype=np.int32)
     state['position_ids']=np.array([pos],dtype=np.int32);state['attention_mask']=np.full(shapes['attention_mask'][0],-100.,dtype=np.float32);state['attention_mask'][...,-(pos+1):]=0.
     values=dec.run(None,state);named=dict(zip(original['meta']['decoder']['output_names'],values));token=int(named['logits'].reshape(-1).argmax())
     if pos>=len(forced)-1:
      if token==50257:stop='eos';break
      tokens.append(token)
     for key in list(state):
      if key.startswith(('k_cache_self_','v_cache_self_')):state[key]=named[key.removesuffix('_in')+'_out']
    text=tok.decode(tokens,skip_special_tokens=True).strip();ref=words(row['reference_source']);hyp=words(text)
    result[mode]=dict(text=text,tokens=tokens,stop=stop,errors=edits(ref,hyp),reference_words=len(ref),elapsed_ms=(time.perf_counter()-started)*1000)
   result['same_text']=result['fp32']['text']==result['w8a16']['text'];rows.append(result);log.write(json.dumps(result,ensure_ascii=False)+'\n');log.flush()
   if (index+1)%10==0:print('PHO_PROGRESS',index+1,'/100',flush=True)
 summary=dict(variant=a.variant,decoder_precision='fp32' if a.variant.startswith('encoder-') else 'w8a16',n=len(rows),same_text=sum(r['same_text'] for r in rows),scope='CPU ONNX full ASR, actual AI Hub quantized graphs; not QNN/device E2E')
 for mode in ['fp32','w8a16']:
  e=sum(r[mode]['errors'] for r in rows);n=sum(r[mode]['reference_words'] for r in rows);summary[mode]=dict(errors=e,reference_words=n,wer=e/n,token_cap=sum(r[mode]['stop']=='token_cap' for r in rows))
 (out/'summary.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary,indent=2))
if __name__=='__main__':main()
