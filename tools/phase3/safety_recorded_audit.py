#!/usr/bin/env python3
"""Replay saved QDC translations through before/after core logic. Does not rerun models/audio."""
import json,subprocess,statistics
from collections import Counter
from pathlib import Path
root=Path(__file__).resolve().parents[2];out=root/'output/phase3/safety-logic-20261114';out.mkdir(parents=True,exist_ok=True)
rows=[]
for folder,model in [('bytebuffers-envit5-btc','envit5'),('bytebuffers-vinai-btc','vinai-vi2en'),('bytebuffers-vinai-btc','vinai-en2vi')]:
 rows += [json.loads(x) for x in (root/'output/qdc-bench/optimized'/folder/(model+'.jsonl')).read_text().splitlines()]
assert len(rows)==400
payload=''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows);(out/'pairs400.jsonl').write_text(payload)
java='/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home/bin/java';cache=root/'tools/e2e-jvm/.cache';summary={};results={}
for mode,jar in [('before','build/safety-red/tests.jar'),('after','build/safety-final-verified/tests.jar')]:
 cp=':'.join(str(x) for x in [root/'build/safety-audit/cli.jar',root/jar,cache/'json-20240303.jar',cache/'kotlinc-2.0.21/lib/kotlin-stdlib.jar'])
 run=subprocess.run([java,'-Xmx256m','-cp',cp,'SafetyAuditCliKt'],input=payload,text=True,capture_output=True,check=True)
 (out/(mode+'.jsonl')).write_text(run.stdout);data=[json.loads(x) for x in run.stdout.splitlines()];assert len(data)==400;results[mode]=data
 times=sorted(r['core_ms'] for r in data)
 summary[mode]=dict(n=len(data),decisions=dict(Counter(r['decision'] for r in data)),speakable=sum(r['speakable'] for r in data),core_host_p50_ms=statistics.median(times),core_host_p95_ms=times[int((len(times)-1)*.95)])
changed=[]
for pair,a,b in zip(rows,results['before'],results['after']):
 if a['decision']!=b['decision'] or a['reason']!=b['reason']:changed.append(dict(source=pair['source'],hypothesis=pair['hypothesis'],reference=pair['reference'],before=a,after=b))
(out/'changes.json').write_text(json.dumps(changed,indent=2,ensure_ascii=False));summary['changed']=len(changed)
summary['scope']='400 recorded clean-source QDC MT outputs through real core only, no ASR/MT/TTS inference; reference text is AI-draft, not human safety labels. Host core timing only, not phone/pipeline latency.'
(out/'summary.json').write_text(json.dumps(summary,indent=2));print(json.dumps(summary,indent=2))
