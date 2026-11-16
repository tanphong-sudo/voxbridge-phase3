#!/usr/bin/env python3
"""WER and chrF on completed QDC outputs; never treats smoke/partial runs as full200."""
import argparse,json,re,unicodedata
from pathlib import Path
import numpy as np

def words(text):return re.findall(r'[^\W_]+',unicodedata.normalize('NFKC',text.casefold()),re.UNICODE)
def edits(a,b):
    row=list(range(len(b)+1))
    for i,x in enumerate(a,1):
        nxt=[i]
        for j,y in enumerate(b,1):nxt.append(min(row[j]+1,nxt[-1]+1,row[j-1]+(x!=y)))
        row=nxt
    return row[-1]
def summarize(model,rows,expected):
    if len({r['id'] for r in rows})!=len(rows):raise ValueError('Duplicate case IDs')
    if not rows:raise ValueError('No scored rows')
    errors=sum(bool(r.get('failure')) for r in rows)
    result=dict(n=len(rows),expected=expected,complete=len(rows)==expected and errors==0,failures=errors,blank=sum(not r['hypothesis'].strip() for r in rows),token_caps=sum(bool(r.get('token_cap')) for r in rows))
    for key in ['graph_run_ms','wall_ms','device_decode_ms']:
        times=[r[key] for r in rows if key in r and not r.get('failure')]
        if times:result[key]=dict(p50=float(np.percentile(times,50)),p95=float(np.percentile(times,95)))
    if model=='phowhisper-small':
        count=sum(len(words(r['reference'])) for r in rows);error=sum(edits(words(r['reference']),words(r['hypothesis'])) for r in rows)
        result.update(word_errors=error,reference_words=count,wer_percent=100*error/count if count else None)
    else:
        from sacrebleu.metrics import CHRF
        result['chrf']=CHRF().corpus_score([r['hypothesis'] for r in rows],[[r['reference'] for r in rows]]).score
        for lang in ['vi','en']:
            group=[r for r in rows if r.get('source_lang')==lang]
            if group:result[lang+'_source_chrf']=CHRF().corpus_score([r['hypothesis'] for r in group],[[r['reference'] for r in group]]).score
    return result

def main():
    p=argparse.ArgumentParser();p.add_argument('--results',type=Path,required=True);p.add_argument('--bundle',type=Path,required=True);a=p.parse_args()
    from run import read_jsonl
    report={'scope':json.loads((a.results/'run.json').read_text()),'scores':{}}
    mt={r['id']:r for r in read_jsonl(a.bundle/'mt200.jsonl')};asr={r['id']:r for r in read_jsonl(a.bundle/'asr-inputs/asr100.jsonl')}
    for model in ['phowhisper-small','envit5','vinai-vi2en','vinai-en2vi']:
        path=a.results/(model+'.jsonl')
        if not path.exists():continue
        rows=read_jsonl(path);data=asr if model=='phowhisper-small' else {k:r for k,r in mt.items() if model=='envit5' or r['source_lang']==('vi' if model.endswith('vi2en') else 'en')}
        for r in rows:
            if r['id'] not in data:raise ValueError('Unknown/wrong-direction case '+r['id'])
            reference=data[r['id']]['reference' if model=='phowhisper-small' else 'reference_target']
            if r['reference']!=reference:raise ValueError('Reference changed for '+r['id'])
        summary=summarize(model,rows,len(data))
        if model=='phowhisper-small':
            baseline_file=a.bundle/'baseline/phowhisper-small/results.jsonl'
            if baseline_file.exists():
                baseline={r['id']:r for r in read_jsonl(baseline_file)}
                summary['same_text_as_existing_fp32']=sum(r['hypothesis']==baseline[r['id']]['hypothesis'] for r in rows)
                matched=[baseline[r['id']] for r in rows]
                count=sum(len(words(r['reference'])) for r in matched)
                error=sum(edits(words(r['reference']),words(r['hypothesis'])) for r in matched)
                summary['existing_fp32_wer_percent_same_subset']=100*error/count if count else None
        if model!='phowhisper-small':
            baseline={r['id']:r for r in read_jsonl(a.bundle/'baseline'/model/'results.jsonl')}
            summary['same_text_as_fp32']=sum(r['hypothesis']==baseline[r['id']]['hypothesis'] for r in rows)
            summary['same_tokens_as_fp32']=sum(r['tokens']==baseline[r['id']]['tokens'] for r in rows)
            refs=[dict(r,graph_run_ms=r.get('elapsed_ms',0),wall_ms=r.get('elapsed_ms',0)) for k,r in baseline.items() if k in {x['id'] for x in rows}]
            summary['fp32_chrf_same_subset']=summarize(model,refs,len(data))['chrf']
        report['scores'][model]=summary
    (a.results/'summary.json').write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n')
    print(json.dumps(report['scores'],indent=2,ensure_ascii=False))
if __name__=='__main__':main()
