#!/usr/bin/env python3
"""All 200 BTC rows, no dev/test selection; paired punctuation variants on two MT models."""
import argparse
import base64
import hashlib
import json
import re
import statistics
import subprocess
from pathlib import Path
from punctuation_ablation import cache_key, validate_results
from mt_pilot import MODELS

VARIANTS=['raw','casing','old_rule','neural_full','neural_terminal']
def read(p):return [json.loads(l) for l in p.read_text().splitlines() if l.strip()]
def write(p,rows):p.write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    p=argparse.ArgumentParser();p.add_argument('command',choices=['prepare','score']);p.add_argument('--root',type=Path,required=True);p.add_argument('--prior',type=Path);p.add_argument('--jar',type=Path);p.add_argument('--java',default='java');p.add_argument('--model',choices=['envit5','vinai']);args=p.parse_args();root=args.root
    if args.command=='prepare':
        if (root/'FREEZE.json').exists():raise FileExistsError('Already frozen')
        sources=read(root/'sources.jsonl');rows=read(root/'horizon/proposals.jsonl')
        assert len(sources)==len(rows)==200 and len({r['id'] for r in rows})==200
        assert {r['id'] for r in sources}=={r['id'] for r in rows}
        vi=[r for r in rows if r['source_lang']=='vi']
        inp=''.join(base64.b64encode(r['input_text'].encode()).decode()+'\n' for r in vi)
        proc=subprocess.run([args.java,'-jar',str(args.jar)],input=inp,text=True,capture_output=True,check=True)
        restored=[base64.b64decode(l).decode() for l in proc.stdout.splitlines()];assert len(restored)==len(vi)
        old={r['id']:s for r,s in zip(vi,restored)}
        for r in rows:
            r['variants']['old_rule']=old.get(r['id'],r['input_text'])
            r['variants']['clean_source']=r['source_text']
        write(root/'variants.jsonl',rows)
        for model in ['envit5','vinai']:
            cache={};dirs=[args.prior/'mt3/runs'/f'{model}-b2-dev100',args.prior/'mt3/runs'/f'{model}-b2-test100-round2',args.prior/'punctuation'/f'punctuation-v2-{model}']
            for d in dirs:
                meta=json.loads((d/'metadata.json').read_text())
                assert meta['num_beams']==2 and meta['max_new_tokens']==192 and not meta.get('dynamic_int8')
                for result in read(d/'results.jsonl'):
                    lang=result['source_lang'];assert result['model_revision']==MODELS[model][lang][1]
                    if result['failure'] or result['stop_reason']=='token_cap' or not result['hypothesis'].strip():continue
                    key=cache_key(result['source_text'],lang)
                    if key in cache and cache[key]['hypothesis']!=result['hypothesis']:raise ValueError('Conflicting deterministic cache')
                    cache[key]=dict(result,reused_from=str(d))
            needed={cache_key(t,r['source_lang']):(t,r['source_lang'],r['target_lang']) for r in rows for t in r['variants'].values()}
            reused=[dict(cache[k],cache_key=k) for k in needed if k in cache]
            missing=[dict(id=k,source_text=t,source_lang=s,target_lang=g,split='dev',reference_target=None,reference_target_original=None,original_provenance=None) for k,(t,s,g) in needed.items() if k not in cache]
            out=root/model;out.mkdir(exist_ok=True);write(out/'missing.jsonl',missing);write(out/'reused.jsonl',reused)
            plan=dict(n_sources=200,unique_inputs=len(needed),reused=len(reused),new_inferences=len(missing),directions={l:sum(r['source_lang']==l for r in missing) for l in ['vi','en']})
            (out/'cache-plan.json').write_text(json.dumps(plan,indent=2)+'\n');print(model,plan)
        (root/'FREEZE.json').write_text(json.dumps(dict(n=200,source_sha256=sha(root/'sources.jsonl'),variant_sha256=sha(root/'variants.jsonl'),old_rule_jar_sha256=sha(args.jar),model_revision='b18abbd2d30e8167e154df444b37faa4d6f5c2ea',threshold=.90,variants=VARIANTS,reference_status='Frozen AI drafts; no human verification',selection='all 200; no dev/test selection',input='synthetic lowercase + ? -> .; lexical ASR errors not simulated'),indent=2)+'\n')
    else:
        from sacrebleu.metrics import CHRF
        from datetime import datetime,timezone
        metric=CHRF(word_order=0);model=args.model;out=root/model;rows=read(root/'variants.jsonl')
        freeze=json.loads((root/'FREEZE.json').read_text());assert sha(root/'variants.jsonl')==freeze['variant_sha256']
        manifest=read(out/'missing.jsonl');new=read(out/'new/results.jsonl');validate_results(new,[r['id'] for r in manifest])
        assert not any(r['failure'] or not r['hypothesis'].strip() or r['stop_reason']=='token_cap' for r in new)
        newmeta=json.loads((out/'new/metadata.json').read_text());assert newmeta['num_beams']==2 and newmeta['max_new_tokens']==192 and not newmeta.get('dynamic_int8')
        actual={}
        for r in read(out/'reused.jsonl')+new:
            lang=r['source_lang'];assert r['model_revision']==MODELS[model][lang][1]
            key=cache_key(r['source_text'],lang);actual[key]=r
        review={r['id']:r for r in read(root/'question-review.jsonl')} if (root/'question-review.jsonl').exists() else {}
        detail=[]
        for r in rows:
            outputs={v:actual[cache_key(t,r['source_lang'])]['hypothesis'] for v,t in r['variants'].items()}
            scores={v:metric.sentence_score(t,[r['reference_target']]).score for v,t in outputs.items()}
            annotation=review.get(r['id'])
            if annotation:assert annotation['source_sha256']==hashlib.sha256(r['source_text'].encode()).hexdigest()
            detail.append(dict(r,outputs=outputs,sentence_chrf=scores,question_review=annotation))
        report={};lexical=lambda t:' '.join(re.findall(r'[^\W_]+',t.casefold()))
        for label,subset in [('all200',detail),('vi100',[r for r in detail if r['source_lang']=='vi']),('en100',[r for r in detail if r['source_lang']=='en'])]:
            report[label]={'n':len(subset),'variants':{}}
            for v in VARIANTS+['clean_source']:
                scores={}
                for key,transform in [('chrf',lambda s:s),('lexical_chrf',lexical)]:
                    scores[key]=metric.corpus_score([transform(r['outputs'][v]) for r in subset],[[transform(r['reference_target']) for r in subset]]).score
                for field in ['input','output']:
                    f=(lambda r:r['variants'][v]) if field=='input' else (lambda r:r['outputs'][v])
                    scores[field+'_question_tp']=sum(r['expected_question'] and f(r).endswith('?') for r in subset)
                    scores[field+'_question_fp']=sum(not r['expected_question'] and f(r).endswith('?') for r in subset)
                reviewed=[r for r in subset if r['question_review'] and r['question_review']['terminal_question'] is not None]
                for field in ['input','output']:
                    f=(lambda r:r['variants'][v]) if field=='input' else (lambda r:r['outputs'][v])
                    scores[field+'_reviewed_question_tp']=sum(r['question_review']['terminal_question'] and f(r).endswith('?') for r in reviewed)
                    scores[field+'_reviewed_question_fp']=sum(not r['question_review']['terminal_question'] and f(r).endswith('?') for r in reviewed)
                scores['reviewed_n']=len(reviewed)
                scores['reviewed_questions']=sum(r['question_review']['terminal_question'] for r in reviewed)
                scores['sentence_chrf_higher_than_raw']=sum(r['sentence_chrf'][v]>r['sentence_chrf']['raw']+1e-8 for r in subset)
                scores['sentence_chrf_lower_than_raw']=sum(r['sentence_chrf'][v]<r['sentence_chrf']['raw']-1e-8 for r in subset)
                scores['identical_output_to_raw']=sum(r['outputs'][v]==r['outputs']['raw'] for r in subset)
                report[label]['variants'][v]=scores
        if (root/'horizon-timing/proposals.jsonl').exists():
            timed=read(root/'horizon-timing/proposals.jsonl')
            assert len(timed)==200 and all(a['id']==b['id'] and all(a['variants'][v]==b['variants'][v] for v in ['raw','casing','neural_full','neural_terminal']) for a,b in zip(rows,timed))
        else:timed=rows
        times=sorted(r['model_ms'] for r in timed[1:]);timing=dict(n=len(times),p50_ms=statistics.median(times),p95_ms=times[round((len(times)-1)*.95)],scope='VPS ONNX session.run only, excludes tokenizer, postprocessing and MT; first call excluded; not Snapdragon')
        if 'total_ms' in timed[0]:
            totals=sorted(r['total_ms'] for r in timed[1:]);timing['full_step_p50_ms']=statistics.median(totals);timing['full_step_p95_ms']=totals[round((len(totals)-1)*.95)]
            timing['full_step_scope']='VPS tokenizer + classifier + postprocessing; concurrent MT processes running; not Snapdragon'
            meta=json.loads((root/'horizon-timing/metadata.json').read_text());timing['process_peak_rss_mib']=meta['process_peak_rss_kib']/1024
        summary=dict(model=model,n=200,groups=report,punctuation_timing=timing,new_inferences=len(new),reused_inputs=len(read(out/'reused.jsonl')),reference_status=freeze['reference_status'],input_scope=freeze['input'],finished_at=datetime.now(timezone.utc).isoformat())
        (out/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n');write(out/'paired-details.jsonl',detail)
        print(json.dumps(summary,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
