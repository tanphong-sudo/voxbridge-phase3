#!/usr/bin/env python3
"""Deduplicate exact MT inputs for a frozen punctuation ablation and score every variant."""
import argparse
import hashlib
import json
from collections import defaultdict
from pathlib import Path
from mt_pilot import MODELS

VARIANTS=['raw','casing','old_rule','neural_full','neural_terminal']
def read(path):return [json.loads(l) for l in path.read_text().splitlines() if l.strip()]
def write(path,rows):path.write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows))
def cache_key(text, source_lang=None):
    payload=text if source_lang is None else source_lang+"\0"+text
    return hashlib.sha256(payload.encode()).hexdigest()
def validate_results(rows,expected):
    if len(rows)!=len({r['id'] for r in rows}) or {r['id'] for r in rows}!=set(expected):raise ValueError('duplicate, missing or unknown output IDs')
def collect_cache(runs):
    cache={}
    for meta,rows in runs:
        if meta.get('num_beams')!=2 or meta.get('max_new_tokens')!=192 or meta.get('dynamic_int8',False):raise ValueError('Incompatible generation settings')
        for r in rows:
            if r.get('failure') or r.get('stop_reason')=='token_cap' or not r.get('hypothesis','').strip():continue
            key=cache_key(r['source_text'])
            if key in cache and cache[key]['hypothesis']!=r['hypothesis']:raise ValueError('Conflicting cached output')
            cache[key]=r
    return cache

def main():
    p=argparse.ArgumentParser();p.add_argument('command',choices=['prepare','score']);p.add_argument('--root',type=Path,required=True);p.add_argument('--prior',type=Path);p.add_argument('--model',choices=['envit5','vinai']);args=p.parse_args()
    root=args.root
    if args.command=='prepare':
        proposals=read(root/'punctuation-next-horizon/proposals.jsonl')
        old={r['id']:r['output'] for r in read(root/'old-rule.jsonl')}
        for r in proposals:r['variants']['old_rule']=old[r['id']]
        write(root/'variants.jsonl',proposals)
        for model in ['envit5','vinai']:
            dirs=[args.prior/'mt3/runs'/f'{model}-b2-dev100',args.prior/'mt3/runs'/f'{model}-b2-test100-round2',args.prior/'punctuation'/f'punctuation-v2-{model}',args.prior/'punctuation'/f'punctuation-v3-extra-{model}']
            runs=[]
            for d in dirs:
                meta=json.loads((d/'metadata.json').read_text());rows=read(d/'results.jsonl')
                for r in rows:
                    if r['source_lang']=='vi' and r['model_revision']!=MODELS[model]['vi'][1]:raise ValueError('Wrong checkpoint')
                    r['reused_from']=str(d)
                runs.append((meta,[r for r in rows if r['source_lang']=='vi']))
            cached=collect_cache(runs)
            needed={cache_key(text):text for r in proposals for text in r['variants'].values()}
            reused={k:cached[k] for k in needed if k in cached}
            missing=[dict(id=k,source_text=t,source_lang='vi',target_lang='en',split='dev',reference_target=None,reference_target_original=None,original_provenance=None) for k,t in needed.items() if k not in reused]
            out=root/model;out.mkdir(exist_ok=True)
            write(out/'missing.jsonl',missing);write(out/'reused.jsonl',[dict(r,cache_key=k) for k,r in reused.items()])
            (out/'cache-plan.json').write_text(json.dumps(dict(n_sources=len(proposals),n_variants=len(VARIANTS),unique_inputs=len(needed),reused=len(reused),new_inferences=len(missing),settings=dict(num_beams=2,max_new_tokens=192,model_revision=MODELS[model]['vi'][1])),indent=2)+'\n')
            print(model,len(needed),len(reused),len(missing))
    else:
        from sacrebleu.metrics import CHRF
        import re,statistics
        metric=CHRF(word_order=0);lexical=lambda s:' '.join(re.findall(r'[^\W_]+',s.casefold()))
        proposals=read(root/'variants.jsonl');model=args.model;out=root/model
        new=read(out/'new/results.jsonl');manifest=read(out/'missing.jsonl');validate_results(new,[r['id'] for r in manifest])
        if any(r['failure'] or not r['hypothesis'].strip() or r['stop_reason']=='token_cap' for r in new):raise ValueError('Failed/capped outputs; must investigate before scoring')
        for r in new:
            if r['id']!=cache_key(r['source_text']) or r['model_revision']!=MODELS[model]['vi'][1]:raise ValueError('New input/revision mismatch')
        actual={cache_key(r['source_text']):r for r in read(out/'reused.jsonl')+new}
        detail=[];groups=defaultdict(list)
        for r in proposals:
            d=dict(r,outputs={v:actual[cache_key(t)]['hypothesis'] for v,t in r['variants'].items()})
            d['sentence_chrf']={v:metric.sentence_score(h,[r['reference_target']]).score for v,h in d['outputs'].items()}
            detail.append(d);groups[r['evaluation_group']].append(d)
        report={}
        for group,rows in groups.items():
            report[group]={'n':len(rows),'variants':{}}
            for v in VARIANTS:
                preds=[r['outputs'][v] for r in rows];refs=[r['reference_target'] for r in rows]
                tp=sum(r['expected_question'] and r['variants'][v].endswith('?') for r in rows);fp=sum(not r['expected_question'] and r['variants'][v].endswith('?') for r in rows)
                report[group]['variants'][v]=dict(chrf=metric.corpus_score(preds,[refs]).score,lexical_chrf=metric.corpus_score([lexical(s) for s in preds],[[lexical(s) for s in refs]]).score,input_questions_tp=tp,input_questions_fp=fp,input_questions_fn=sum(r['expected_question'] for r in rows)-tp,output_questions_tp=sum(r['expected_question'] and r['outputs'][v].endswith('?') for r in rows),output_questions_fp=sum(not r['expected_question'] and r['outputs'][v].endswith('?') for r in rows),higher_than_raw=sum(r['sentence_chrf'][v]>r['sentence_chrf']['raw']+1e-8 for r in rows),lower_than_raw=sum(r['sentence_chrf'][v]<r['sentence_chrf']['raw']-1e-8 for r in rows))
        times=sorted(r['model_ms'] for r in proposals)
        summary=dict(model=model,groups=report,new_outputs=len(new),punctuation_ms=dict(p50=statistics.median(times[1:]),p95=times[int((len(times)-1)*.95)],max=max(times),scope='VPS ONNX classifier only; excludes MT, not Snapdragon'),reference_status='AI-authored draft, human_verified=0',input_scope='synthetic except saved actual_asr_snapshot')
        (out/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n');write(out/'paired-details.jsonl',detail)
        print(json.dumps(summary,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
