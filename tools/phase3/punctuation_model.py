#!/usr/bin/env python3
"""Experimental ONNX punctuation ablations, not an Android backend. No lexical generation."""
import argparse
import hashlib
import json
import re
import time
from pathlib import Path

MARKS = re.compile(r'[.,;:!?。，、；：！？؟،¿¡…]')

def strip_marks(text):
    return MARKS.sub(lambda m: m.group() if m.group() in '.,:' and 0 < m.start() < len(text)-1 and text[m.start()-1].isalnum() and text[m.start()+1].isalnum() else '', text)

def capitalize_initial(text):
    for i,c in enumerate(text):
        if c.isalpha(): return text[:i]+c.upper()+text[i+1:]
    return text

def render_labels(text, labels):
    result=[];prev=0
    for end,label in sorted(labels.items()):
        if not (end==len(text) or text[end].isspace()): continue
        mark,case=label.split('|')
        start=max(prev,text.rfind(' ',0,end)+1)
        word=text[start:end]
        # IDs/numbers and already-present capitals are preserved verbatim.
        if not any(c.isdigit() for c in word) and word==word.lower():
            if case=='Cap': word=capitalize_initial(word)
            elif case=='UPPER': word=word.upper()
        result.append(text[prev:start]+word+('' if mark=='0' else ' -' if mark=='-' else mark));prev=end
    return ''.join(result)+text[prev:]

def terminal_candidate(text, mark, confidence):
    out=capitalize_initial(text)
    if re.search(r'[?!…]|\.{2,}',text): return out
    words=text.strip().rstrip('.').lower()
    blocked=re.search(r'^(?:tôi |chúng tôi |mấy |ai nấy |hãy |đừng |cấm |không |vui lòng |kiểm tra xem )|(?:\b)(?:biết|nhớ|xem|xác nhận|tìm hiểu|trả lời|chưa rõ|không rõ)(?:\b)',words)
    if mark=='?' and confidence>=.90 and not blocked:
        return out.rstrip('.')+'?'
    return out

def main():
    p=argparse.ArgumentParser();p.add_argument('--sources',type=Path,required=True);p.add_argument('--model',type=Path,required=True);p.add_argument('--out',type=Path,required=True);args=p.parse_args()
    import numpy as np
    import onnxruntime as ort
    from tokenizers import Tokenizer
    args.out.mkdir(parents=True,exist_ok=True)
    dest=args.out/'proposals.jsonl'
    if dest.exists():raise FileExistsError(dest)
    opts=ort.SessionOptions();opts.intra_op_num_threads=4;opts.inter_op_num_threads=1
    session=ort.InferenceSession(str(args.model/'onnx/model.onnx'),sess_options=opts,providers=['CPUExecutionProvider'])
    tok=Tokenizer.from_file(str(args.model/'tokenizer.json'))
    config=json.loads((args.model/'config.json').read_text());id2label=config['id2label']
    sources=[json.loads(l) for l in args.sources.read_text().splitlines()]
    print(dict(onnxruntime=ort.__version__,inputs=[(i.name,i.shape) for i in session.get_inputs()]),flush=True)
    for r in sources:
        pipeline_started=time.perf_counter()
        original=r['input_text'];clean=strip_marks(original).strip();lower=clean.lower()
        assert len(lower)==len(clean),'Unsupported lowercase expansion'
        enc=tok.encode(lower,add_special_tokens=True)
        if len(enc.ids)>400:raise ValueError('Explicit token limit; no silent truncation')
        ids=np.array([enc.ids],dtype=np.int64)
        started=time.perf_counter()
        logits=session.run(None,{'input_ids':ids,'attention_mask':np.ones_like(ids)})[0][0]
        ms=(time.perf_counter()-started)*1000
        logits=logits-logits.max(axis=-1,keepdims=True);probs=np.exp(logits);probs/=probs.sum(axis=-1,keepdims=True)
        labels={};terminal=None
        for i,((s,e),special) in enumerate(zip(enc.offsets,enc.special_tokens_mask)):
            if special or e<=s:continue
            labels[e]=id2label[str(int(probs[i].argmax()))]
            if e==len(lower): terminal=probs[i]
        if terminal is None:raise ValueError('No terminal word prediction')
        punct={mark:float(sum(terminal[j] for j in range(len(terminal)) if id2label[str(j)].split('|')[0]==mark)) for mark in ['0','.',',','?',':','-']}
        mark=max(punct,key=punct.get)
        full=render_labels(clean,labels)
        if re.search(r'[?!…]|\.{2,}',original):full=capitalize_initial(original)
        # Compare word/number sequences, including order. Any model-side lexical change fails this run.
        tokens=lambda t:re.findall(r'[^\W_]+',t.casefold())
        if tokens(full)!=tokens(original):raise ValueError('Lexical preservation failed: '+r['id'])
        row=dict(r,variants={'raw':original,'casing':capitalize_initial(original),'neural_full':full,'neural_terminal':terminal_candidate(original,mark,punct[mark])},terminal_probabilities=punct,model_ms=ms,total_ms=(time.perf_counter()-pipeline_started)*1000)
        with dest.open('a') as f:f.write(json.dumps(row,ensure_ascii=False)+'\n')
    import resource
    meta=dict(process_peak_rss_kib=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,model=json.loads((args.model/'provenance.json').read_text()),n=len(sources),sources_sha256=hashlib.sha256(args.sources.read_bytes()).hexdigest(),rule='terminal threshold .90 predeclared; no tuning on outputs',runtime=ort.__version__,threads=4,lexical_generation=False)
    (args.out/'metadata.json').write_text(json.dumps(meta,indent=2)+'\n')
    print(json.dumps(meta),flush=True)
if __name__=='__main__':main()
