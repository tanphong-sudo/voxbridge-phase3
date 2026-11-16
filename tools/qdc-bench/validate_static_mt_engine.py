"""Full Java app engine parity on static CPU exports, including repeat use of workspaces."""
import argparse,json,subprocess,sys
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--repo',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
 a.out.mkdir(parents=True,exist_ok=False);allrows=[json.loads(x) for x in (a.root/'qdc-bench/mt200.jsonl').read_text().splitlines() if x.strip()]
 cache=a.repo/'tools/e2e-jvm/.cache';cp=':'.join(str(p) for p in [a.repo/'build/static-mt-engine',cache/'onnxruntime-1.20.0.jar',cache/'json-20240303.jar'])
 report={'scope':'CPU source ONNX app engine/tokenizer full parity, not QNN or phone latency','models':{}}
 for name in ['envit5','vinai-vi2en','vinai-en2vi']:
  rows=allrows if name=='envit5' else [r for r in allrows if r['source_lang']==('vi' if name.endswith('vi2en') else 'en')]
  probes=rows+[rows[0],rows[-1]]
  inputs=''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in probes)
  cmd=['java','-cp',cp,'StaticMtEngineCli',str(a.root/'mt-static-v2'/name/'encoder/model.onnx'),str(a.root/'mt-static-v2'/name/'decoder/model.onnx'),str(a.root/'qdc-bench/tokenizers'/name)]
  proc=subprocess.run(cmd,input=inputs,text=True,capture_output=True,check=True)
  (a.out/(name+'.stderr')).write_text(proc.stderr);(a.out/(name+'.jsonl')).write_text(proc.stdout)
  output=[json.loads(x) for x in proc.stdout.splitlines() if x.strip()]
  baseline={r['id']:r for r in map(json.loads,(a.root/'mt-static-v2-results/fp32'/name/'results.jsonl').read_text().splitlines())}
  differences=[]
  for r in output:
   if r.get('failure') or r.get('tokens')!=baseline[r['id']]['tokens']:differences.append(r['id'])
  report['models'][name]={'n':len(output),'expected':len(probes),'matching_tokens':len(output)-len(differences),'differences':differences,'passed':len(output)==len(probes) and not differences}
  (a.out/'summary.json').write_text(json.dumps(report,indent=2)+'\n');print(name,report['models'][name],flush=True)
 if not all(r['passed'] for r in report['models'].values()):raise SystemExit('App engine parity failed')
 print('APP_STATIC_MT_FULL_PARITY_PASS',flush=True)
if __name__=='__main__':main()
