#!/usr/bin/env python3
"""Compare before/after production decisions on freshly generated all-200 MT outputs."""
import argparse,hashlib,json,subprocess
from pathlib import Path
from collections import Counter
from mt_pilot import MODELS

def read(p):return [json.loads(l) for l in p.read_text().splitlines() if l.strip()]
def write(p,rows):p.write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows))
def replay(java,cli,tests,json_jar,rows):
 cp=':'.join(map(str,[cli,tests,json_jar]));r=subprocess.run([java,'-cp',cp,'CoverageCliKt'],input=''.join(json.dumps(x,ensure_ascii=False)+'\n' for x in rows),text=True,capture_output=True,check=True)
 result=[json.loads(l) for l in r.stdout.splitlines()]
 if len(result)!=len(rows) or [x['id'] for x in result]!=[x['id'] for x in rows]:raise ValueError('Pipeline replay IDs mismatch')
 return result

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--before-tests',type=Path,required=True);p.add_argument('--after-tests',type=Path,required=True);p.add_argument('--before-cli',type=Path,required=True);p.add_argument('--after-cli',type=Path,required=True);p.add_argument('--json-jar',type=Path,required=True);p.add_argument('--java',default='java');p.add_argument('--controls',type=Path,required=True);args=p.parse_args();root=args.root
 from sacrebleu.metrics import CHRF
 metric=CHRF(word_order=0);manifest=read(root/'manifest200.jsonl');expected={r['id']:r for r in manifest};assert len(manifest)==len(expected)==200
 if sorted(Counter(r['source_lang'] for r in manifest).values())!=[100,100]:raise ValueError('Wrong direction counts')
 report=dict(scope='Fresh FP32 beam2 MT on all 200 BTC texts per model, followed by real Kotlin DecisionEngine replay with valid synthetic audio evidence. Not 200 WAVs or Android/QDC.',models={},controls={},code_hashes={})
 all_rows=[]
 for model in ['envit5','vinai']:
  results=read(root/model/'results.jsonl');meta=json.loads((root/model/'metadata.json').read_text());assert meta['num_beams']==2 and meta['max_new_tokens']==192 and not meta['dynamic_int8']
  assert len(results)==200 and {r['id'] for r in results}==set(expected) and len({r['id'] for r in results})==200
  if any(r['failure'] or not r['hypothesis'].strip() or r['stop_reason']=='token_cap' for r in results):raise ValueError('Missing/failed/capped inference')
  rows=[]
  for r in results:
   src=expected[r['id']];assert r['source_text']==src['source_text'] and r['model_revision']==MODELS[model][r['source_lang']][1]
   rows.append(dict(id=model+'::'+r['id'],source_lang=r['source_lang'],source_text=r['source_text'],target_text=r['hypothesis'],reference_target=src['reference_target'],parent_id=r['id'],model=model))
  before=replay(args.java,args.before_cli,args.before_tests,args.json_jar,rows);after=replay(args.java,args.after_cli,args.after_tests,args.json_jar,rows)
  details=[dict(r,before=b,after=a) for r,b,a in zip(rows,before,after)];all_rows+=details
  changed=[r['parent_id'] for r in details if r['before']['pipeline_detail']!=r['after']['pipeline_detail']]
  blocked=lambda k:[r['parent_id'] for r in details if r[k]['pipeline_detail']=='AskRepeat(reason=translation_degenerate)']
  report['models'][model]=dict(n=200,new_inferences=200,before_decisions=dict(Counter(r['before']['pipeline_decision'] for r in details)),after_decisions=dict(Counter(r['after']['pipeline_decision'] for r in details)),collapse_blocked_before=blocked('before'),collapse_blocked_after=blocked('after'),changed_decision_ids=changed,new_collapse_blocks=sorted(set(blocked('after'))-set(blocked('before'))),raw_chrf=metric.corpus_score([r['target_text'] for r in rows],[[r['reference_target'] for r in rows]]).score,reference_status='AI draft, human_verified=false')
  write(root/model/'pipeline-comparison.jsonl',details)
 controls=read(args.controls);before=replay(args.java,args.before_cli,args.before_tests,args.json_jar,controls);after=replay(args.java,args.after_cli,args.after_tests,args.json_jar,controls)
 control_rows=[dict(r,before=b,after=a) for r,b,a in zip(controls,before,after)];write(root/'control-pipeline-comparison.jsonl',control_rows)
 for label in ['good','bad']:
  subset=[r for r in control_rows if r['label']==label]
  report['controls'][label]=dict(n=len(subset),collapse_blocks_before=sum(r['before']['pipeline_detail']=='AskRepeat(reason=translation_degenerate)' for r in subset),collapse_blocks_after=sum(r['after']['pipeline_detail']=='AskRepeat(reason=translation_degenerate)' for r in subset))
 for name,path in [('before-tests',args.before_tests),('after-tests',args.after_tests),('manifest',root/'manifest200.jsonl')]:report['code_hashes'][name]=hashlib.sha256(path.read_bytes()).hexdigest()
 (root/'summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
