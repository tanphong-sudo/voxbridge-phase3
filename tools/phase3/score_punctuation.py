#!/usr/bin/env python3
"""Paired punctuation ablation scores from saved outputs, without model reruns."""
import argparse
import json
import re
import statistics
from pathlib import Path
from sacrebleu.metrics import CHRF


def read(p): return [json.loads(l) for l in p.read_text().splitlines() if l.strip()]
def lexical(s): return ' '.join(re.findall(r'[^\W_]+',s.casefold(),re.UNICODE))
def main():
    p=argparse.ArgumentParser();p.add_argument('--pairs',type=Path,required=True);p.add_argument('--runs',type=Path,required=True);p.add_argument('--run-prefix',default='punctuation-v2-');p.add_argument('--out',type=Path,required=True);args=p.parse_args()
    pairs=read(args.pairs);metric=CHRF(word_order=0);report={'reference_status':'AI draft, no human verification','input_status':'synthetic lowercase + ? replaced by .; regression set, not blind acceptance','models':{}}
    for model in ['envit5','vinai']:
        run=args.runs/(args.run_prefix+model);rows=read(run/'results.jsonl');actual={r['id']:r for r in rows}
        expected={r['id']+'::'+v for r in pairs for v in ['before','after']}
        assert len(rows)==len(expected)==len(actual) and set(actual)==expected
        assert not any(r['failure'] or not r['hypothesis'].strip() or r['stop_reason']=='token_cap' for r in rows)
        detail=[]
        for r in pairs:
            b=actual[r['id']+'::before'];a=actual[r['id']+'::after'];ref=r['reference_target']
            assert b['source_text']==r['degraded'] and a['source_text']==r['restored']
            score=lambda s:metric.sentence_score(s,[ref]).score
            detail.append(dict(r,before=b['hypothesis'],after=a['hypothesis'],before_chrf=score(b['hypothesis']),after_chrf=score(a['hypothesis']),delta_chrf=score(a['hypothesis'])-score(b['hypothesis'])))
        summaries={}
        for group in ['btc','control','all','btc_questions','btc_statements','extra']:
            subset=[r for r in detail if group=='all' or r['group']==group or (r['group']=='btc' and group==('btc_questions' if r['expected_question'] else 'btc_statements'))]
            if not subset: continue
            d={'n':len(subset)}
            for v in ['before','after']:
                d[v]={}
                for label,transform in [('chrf',lambda s:s),('lexical_chrf',lexical)]:
                    d[v][label]=metric.corpus_score([transform(r[v]) for r in subset],[[transform(r['reference_target']) for r in subset]]).score
                times=sorted(actual[r['id']+'::'+v]['latency_ms'] for r in subset)
                d[v]['mt_ms_p50']=statistics.median(times);d[v]['mt_ms_p95']=times[round((len(times)-1)*.95)]
                d[v]['question_marks_on_questions']=sum(r['expected_question'] and r[v].endswith('?') for r in subset)
                d[v]['question_marks_on_statements']=sum(not r['expected_question'] and r[v].endswith('?') for r in subset)
            d['higher_sentence_chrf']=sum(r['delta_chrf']>1e-8 for r in subset);d['lower_sentence_chrf']=sum(r['delta_chrf']< -1e-8 for r in subset)
            d['same_sentence_chrf']=len(subset)-d['higher_sentence_chrf']-d['lower_sentence_chrf']
            d['identical_output']=sum(r['before']==r['after'] for r in subset)
            summaries[group]=d
        report['models'][model]=summaries
        (run/'paired-details.jsonl').write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in detail))
    args.out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__': main()
