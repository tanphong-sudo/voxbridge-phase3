#!/usr/bin/env python3
"""Complete sentence QNN quality benchmark, host HF beam search with device-resident KV."""
import argparse,hashlib,json,re,time,sys
from pathlib import Path
import numpy as np

def read_jsonl(path):return [json.loads(x) for x in Path(path).read_text().splitlines() if x.strip()]
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def asr_decode(bridge,specs,mel):
    if hasattr(bridge,'asr_fast'):return bridge.asr_fast(mel)
    bridge.reset();outputs=bridge.run('encoder',{'input_features':mel})
    cross={k:v for k,v in outputs.items() if k.startswith(('k_cache_cross_','v_cache_cross_'))}
    bridge.drop([v for k,v in outputs.items() if k not in cross]);state={}
    for name,meta in specs.items():
        if name.endswith('_in') and '_cache_self_' in name:state[name]=bridge.zeros(meta['shape'])
    forced=[50258,50278,50359,50363];tokens=[];token=forced[0];stop='token_cap'
    for pos in range(199):
        mask=np.full((1,1,1,200),-100.,dtype=np.float32);mask[...,-(pos+1):]=0.
        feeds={**cross,**state,'input_ids':np.array([[forced[pos] if pos<4 else token]],dtype=np.int32),
               'position_ids':np.array([pos],dtype=np.int32),'attention_mask':mask}
        result=bridge.run('decoder',{k:v for k,v in feeds.items() if k in specs})
        logits=bridge.get(result['logits']);token=int(logits.reshape(-1).argmax())
        bridge.drop(list(state.values())+[result['logits']]);state={k:result[k.removesuffix('_in')+'_out'] for k in state}
        if pos>=3:
            if token==50257:stop='eos';break
            tokens.append(token)
    return tokens,stop

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bundle',type=Path,required=True);p.add_argument('--out',type=Path,required=True)
    p.add_argument('--models',nargs='+',choices=['phowhisper-small','envit5','vinai-vi2en','vinai-en2vi'],default=['phowhisper-small','envit5','vinai-vi2en','vinai-en2vi'])
    p.add_argument('--host',default='127.0.0.1');p.add_argument('--port',type=int,default=18765);p.add_argument('--token-file',type=Path)
    p.add_argument('--cpu-root',type=Path,help='Validation only: source static FP32 ONNX, never reported as QNN')
    p.add_argument('--reuse-buffers',action='store_true',default=True)
    p.add_argument('--no-reuse-buffers',dest='reuse_buffers',action='store_false',help='Historical slow path, diagnostic comparison only')
    p.add_argument('--limit',type=int,default=0,help='Smoke only; zero means all cases');a=p.parse_args()
    import torch
    from transformers import AutoTokenizer,AutoConfig,GenerationConfig
    from mt_runtime import RemoteMt
    torch.set_num_threads(3)
    bundle=a.bundle.resolve();a.out.mkdir(parents=True,exist_ok=True);models=json.loads((bundle/'models/models.json').read_text())
    bridge=None;backend='CPU validation' if a.cpu_root else 'QNN HTP'
    if not a.cpu_root:
        if not a.token_file:p.error('--token-file required for QDC')
        from remote import Remote
        bridge=Remote(a.host,a.port,a.token_file.read_text().strip());bridge.reuse_buffers=a.reuse_buffers;device=bridge.request('hello')
    else:device={'backend':'CPU validation only'}
    freeze=dict(reuse_buffers=a.reuse_buffers,backend=backend,device=device,models=models,dataset_sha256=sha(bundle/'mt200.jsonl'),asr_sha256=sha(bundle/'asr-inputs/asr100.jsonl'),limit=a.limit,
        timing_scope='graph_run_ms sums phone ORT run calls only. device_decode_ms includes full phone-side ASR/MT decoding and tensor/beam handling; excludes host preprocessing/tokenization, network and model loading. wall_ms includes host/transport. None is final audio-to-speech pipeline latency.')
    freeze_file=a.out/'run.json'
    if freeze_file.exists():
        old=json.loads(freeze_file.read_text())
        if old!=freeze:raise RuntimeError('Refusing to resume with a different model/dataset/device/limit')
    else:freeze_file.write_text(json.dumps(freeze,indent=2,ensure_ascii=False)+'\n')
    try:
        for model in a.models:
            is_asr=model=='phowhisper-small'
            datafile=bundle/('asr-inputs/asr100.jsonl' if is_asr else 'mt200.jsonl');rows=read_jsonl(datafile)
            if model.startswith('vinai'):rows=[r for r in rows if r['source_lang']==('vi' if model.endswith('vi2en') else 'en')]
            if a.limit:rows=rows[:a.limit]
            output=a.out/(model+'.jsonl');previous=read_jsonl(output) if output.exists() else [];done={r['id'] for r in previous}
            if all(r['id'] in done for r in rows):continue
            if a.cpu_root:
                from cpu_bridge import CpuBridge
                base=a.cpu_root/('phowhisper-small-static' if is_asr else 'mt-static-v2/'+model)
                bridge=CpuBridge({part:base/part/'model.onnx' for part in ['encoder','decoder']});specs=bridge.specs
            else:
                loaded=bridge.request('load',models={part:'models/'+models[model+'/'+part]['onnx'] for part in ['encoder','decoder']})
                specs={part:loaded[part] for part in ['encoder','decoder']}
                (a.out/(model+'-load.json')).write_text(json.dumps(loaded,indent=2)+'\n')
            tok=AutoTokenizer.from_pretrained(str(bundle/'tokenizers'/model),local_files_only=True)
            if not is_asr:
                cfg=AutoConfig.from_pretrained(str(bundle/'tokenizers'/model),local_files_only=True)
                generation=GenerationConfig.from_pretrained(str(bundle/'tokenizers'/model),local_files_only=True)
                runtime=RemoteMt(cfg,generation,bridge,specs['decoder']['inputs'])
            def infer(row):
                if is_asr:
                    mel=np.fromfile(bundle/'asr-inputs'/row['mel'],dtype='<f4').reshape(1,80,3000)
                    tokens,stop=asr_decode(bridge,specs['decoder']['inputs'],mel)
                    return tok.decode(tokens,skip_special_tokens=True).strip(),tokens,stop=='token_cap'
                src=row['source_lang'];kind='envit5' if model=='envit5' else 'vinai'
                if kind=='vinai':tok.src_lang='vi_VN' if src=='vi' else 'en_XX'
                text=src+': '+row['source_text'] if kind=='envit5' else row['source_text']
                encoded=tok(text,return_tensors='pt',padding='max_length',max_length=64,truncation=False)
                if encoded['input_ids'].shape[1]!=64:raise ValueError('Source exceeds64; no silent truncation')
                kwargs=dict(num_beams=2,max_new_tokens=192,do_sample=False)
                if kind=='vinai':kwargs.update(decoder_start_token_id=tok.lang_code_to_id['en_XX' if src=='vi' else 'vi_VN'],forced_bos_token_id=None,early_stopping=True)
                if hasattr(bridge,'mt_fast'):
                    start=kwargs.get('decoder_start_token_id',cfg.decoder_start_token_id)
                    forced=generation.forced_eos_token_id
                    ids,cap=bridge.mt_fast(encoded['input_ids'].numpy().astype(np.int32),encoded['attention_mask'].numpy().astype(np.int32),start,cfg.eos_token_id,kind=='vinai',forced if forced is not None else -1)
                    sequence=torch.tensor([ids],dtype=torch.int64)
                else:
                    with torch.inference_mode():sequence=runtime.generate(**encoded,**kwargs)
                text=tok.decode(sequence[0],skip_special_tokens=True).strip()
                if kind=='envit5':text=re.sub(r'^\s*(?:en|vi)\s*:\s*','',text,count=1,flags=re.I).strip()
                return text,sequence[0].tolist(),sequence.shape[1]-1>=192
            # First sentence warmup excluded from scored timing, recorded separately.
            started=time.perf_counter();infer(rows[0]);warm=dict(wall_ms=(time.perf_counter()-started)*1000,graph_run_ms=bridge.run_ms)
            (a.out/(model+'-warmup.json')).write_text(json.dumps(warm,indent=2)+'\n')
            with output.open('a') as log:
                for i,row in enumerate(rows):
                    if row['id'] in done:continue
                    record=dict(id=row['id'],model=model,backend=backend,reference=row['reference'] if is_asr else row['reference_target'])
                    if not is_asr:record.update(source_lang=row['source_lang'],source=row['source_text'])
                    started=time.perf_counter()
                    # A device/transport error ends this run; never turns into silent CPU fallback.
                    text,tokens,cap=infer(row)
                    record.update(hypothesis=text,tokens=tokens,token_cap=cap,graph_run_ms=bridge.run_ms,output_copy_ms=bridge.copy_ms,graph_calls=bridge.calls,wall_ms=(time.perf_counter()-started)*1000,failure=None)
                    if hasattr(bridge,'last_timing'):record['timing_breakdown']=bridge.last_timing
                    if hasattr(bridge,'device_decode_ms'):record['device_decode_ms']=bridge.device_decode_ms
                    log.write(json.dumps(record,ensure_ascii=False)+'\n');log.flush()
                    print(f'{backend} {model} {i+1}/{len(rows)} graph_ms={bridge.run_ms:.1f} cap={cap}',flush=True)
            if a.cpu_root:bridge.close()
            else:
                profiles=bridge.request('unload');(a.out/(model+'-profiles.json')).write_text(json.dumps(profiles,indent=2)+'\n')
        print('RUN_FINISHED: use score.py; verify expected case counts before accepting',flush=True)
    finally:
        if bridge:bridge.close()
if __name__=='__main__':main()
