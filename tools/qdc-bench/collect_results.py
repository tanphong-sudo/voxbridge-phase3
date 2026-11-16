"""Collect finished profiles, confirm QNN placement, and preserve all exact scored outputs."""
import json,collections,subprocess
from pathlib import Path
root=Path('/home/opc/voxbridge-mt3-20261113/qdc-bench');audit={}
for folder,models in [('qdc-release-btc',['phowhisper-small','vinai-vi2en','vinai-en2vi']),('qdc-envit5-repaired-btc',['envit5'])]:
 out=root/folder/'native-profiles';out.mkdir(exist_ok=True)
 for model in models:
  paths=json.loads((root/folder/(model+'-profiles.json')).read_text())['profiles'];audit[model]={}
  for part,path in paths.items():
   target=out/(model+'-'+part+'.json')
   subprocess.run(['/home/opc/android-sdk/platform-tools/adb','-s','80003b9c','pull',path,str(target)],check=True,capture_output=True)
   events=json.loads(target.read_text());counts=collections.Counter(x.get('args',{}).get('provider') for x in events if x.get('args',{}).get('provider'))
   assert counts and not any(provider!='QNNExecutionProvider' for provider in counts),dict(counts)
   audit[model][part]=dict(counts)
(root/'qnn-provider-audit.json').write_text(json.dumps(audit,indent=2)+'\n');print(json.dumps(audit,indent=2))
# Preserve the one capped result with its exact FP32 baseline for actionable follow-up.
rows=[json.loads(x) for x in (root/'qdc-envit5-repaired-btc/envit5.jsonl').read_text().splitlines()]
baseline={r['id']:r for r in [json.loads(x) for x in (root/'baseline/envit5/results.jsonl').read_text().splitlines()]}
capped=[dict(qnn=r,fp32=baseline[r['id']]) for r in rows if r.get('token_cap')]
(root/'envit5-capped-cases.json').write_text(json.dumps(capped,indent=2,ensure_ascii=False)+'\n')
print('CAPPED',[(x['qnn']['id'],x['qnn']['source'],x['qnn']['hypothesis'][:240]) for x in capped])
print('ACTUAL_CPU_BEAM_PARITY',len(json.loads((root/'actual-beam-all-parity.json').read_text())))
