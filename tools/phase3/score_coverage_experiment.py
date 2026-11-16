#!/usr/bin/env python3
"""Summarize controlled and actual-output evidence without claiming heuristic semantic certainty."""
import argparse,json,statistics,subprocess
from pathlib import Path

def read(p):return [json.loads(l) for l in p.read_text().splitlines() if l.strip()]
def write(p,rows):p.write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows))
def confusion(rows,detector):
 tp=fp=fn=tn=0
 for r in rows:
  bad=r['label']=='bad';pred=detector(r['diagnostic'])
  if bad and pred:tp+=1
  elif bad:fn+=1
  elif pred:fp+=1
  else:tn+=1
 return dict(tp=tp,fp=fp,fn=fn,tn=tn,precision=tp/(tp+fp) if tp+fp else None,recall=tp/(tp+fn) if tp+fn else None)

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--java',required=True);p.add_argument('--cli',type=Path,required=True);p.add_argument('--tests',type=Path,required=True);p.add_argument('--json-jar',type=Path,required=True);args=p.parse_args();root=args.root
 from sacrebleu.metrics import CHRF
 metric=CHRF(word_order=0)
 controls=read(root/'controls-diagnostics.jsonl');actual=read(root/'actual-diagnostics.jsonl');review={r['id']:r for r in read(root/'actual-review.jsonl')}
 detectors={'baseline':lambda d:d['baseline_failure'],'hard_failure':lambda d:d['hard_failure'],'suspect':lambda d:d['hard_failure'] or bool(d['missing_units'])}
 report={'reference_status':'AI-authored controls/reference/review, human_verified=0','controls':{name:confusion(controls,fn) for name,fn in detectors.items()},'actual':{},'retry':{}}
 for kind in ['blank','phrase_loop','missing','missing_padded','full','compact']:
  subset=[r for r in controls if r['error_type']==kind];report['controls'][kind]={name:confusion(subset,fn) for name,fn in detectors.items()}
 reviewed=[]
 for r in actual:
  if r['id'] in review and review[r['id']]['coverage_label']!='ambiguous':reviewed.append(dict(r,label='bad' if review[r['id']]['coverage_label']=='missing' else 'good'))
 report['actual']['reviewed']={name:confusion(reviewed,fn) for name,fn in detectors.items()};report['actual']['reviewed_n']=len(reviewed)
 for model in ['envit5','vinai']:
  subset=[r for r in actual if r['model']==model];report['actual'][model]=dict(n=len(subset),hard=sum(r['diagnostic']['hard_failure'] for r in subset),missing_cue=sum(bool(r['diagnostic']['missing_units']) for r in subset),retryable=sum(bool(r['diagnostic']['retry_sentences']) for r in subset),any_diagnostic=sum(bool(r['diagnostic']['reasons']) for r in subset))
 parents=read(root/'parents.jsonl');all_details=[];cli_rows=[]
 for model in ['envit5','vinai']:
  path=root/model/'results.jsonl'
  if not path.exists():raise FileNotFoundError(path)
  results=read(path);assert len(results)==120 and len({r['id'] for r in results})==120
  assert not any(r['failure'] or not r['hypothesis'].strip() or r['stop_reason']=='token_cap' for r in results)
  actual_outputs={r['id']:r for r in results};details=[]
  for r in parents:
   full=actual_outputs[r['id']+'::full'];segments=[actual_outputs[r['id']+f'::segment-{i}'] for i in range(2)];joined=' '.join(s['hypothesis'].strip() for s in segments)
   row=dict(r,model=model,first=full['hypothesis'],retry=joined,first_ms=full['latency_ms'],retry_ms=sum(s['latency_ms'] for s in segments),first_chrf=metric.sentence_score(full['hypothesis'],[r['reference_target']]).score,retry_chrf=metric.sentence_score(joined,[r['reference_target']]).score)
   details.append(row)
   for variant,text in [('first',row['first']),('retry',joined)]:cli_rows.append(dict(id=model+'::'+r['id']+'::'+variant,source_text=r['source_text'],source_lang=r['source_lang'],target_text=text))
  report['retry'][model]=dict(n=40,first_chrf=metric.corpus_score([r['first'] for r in details],[[r['reference_target'] for r in details]]).score,retry_chrf=metric.corpus_score([r['retry'] for r in details],[[r['reference_target'] for r in details]]).score,higher_sentence_chrf=sum(r['retry_chrf']>r['first_chrf']+1e-8 for r in details),lower_sentence_chrf=sum(r['retry_chrf']<r['first_chrf']-1e-8 for r in details),first_ms_p50=statistics.median(r['first_ms'] for r in details),extra_retry_ms_p50=statistics.median(r['retry_ms'] for r in details),scope='VPS CPU, concurrent unrelated workload and scheduling changes; not a reliable phone or pipeline latency benchmark')
  all_details+=details
 write(root/'retry-details.jsonl',all_details)
 cp=':'.join(str(x) for x in [args.cli,args.tests,args.json_jar]);proc=subprocess.run([args.java,'-cp',cp,'CoverageCliKt'],input=''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in cli_rows),text=True,capture_output=True,check=True);diagnostics=[json.loads(l) for l in proc.stdout.splitlines()];assert len(diagnostics)==160
 write(root/'retry-diagnostics.jsonl',[dict(r,diagnostic=d) for r,d in zip(cli_rows,diagnostics)])
 lookup={r['id']:r for r in diagnostics}
 for model in ['envit5','vinai']:
  subset=[r for r in all_details if r['model']==model];triggered=[r for r in subset if lookup[model+'::'+r['id']+'::first']['retry_sentences']]
  out=report['retry'][model];out['automatic_retry_candidates']=len(triggered);out['candidate_higher_chrf']=sum(r['retry_chrf']>r['first_chrf']+1e-8 for r in triggered);out['candidate_lower_chrf']=sum(r['retry_chrf']<r['first_chrf']-1e-8 for r in triggered)
  out['candidate_ids']=[r['id'] for r in triggered]
  out['post_retry_pipeline_decisions']={k:sum(lookup[model+'::'+r['id']+'::retry']['pipeline_decision']==k for r in subset) for k in ['AskRepeat','RepairedTranslation','VerifiedTranslation','ModelTranslation']}
 (root/'summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
