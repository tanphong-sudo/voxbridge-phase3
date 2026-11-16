import sys,json,time
from pathlib import Path
import numpy as np
from remote import Remote
root=Path('/home/opc/voxbridge-mt3-20261113/qdc-bench');out=root/sys.argv[1];out.mkdir(exist_ok=True)
bridge=Remote('127.0.0.1',28900,(root/'.session-token').read_text().strip());device=bridge.request('hello');(out/'device.json').write_text(json.dumps(device,indent=2))
rows=[json.loads(x) for x in (root/'mt200.jsonl').read_text().splitlines()]
# First sentence of BTC reference text; mark reference/full distinction and do not claim product normalizer parity.
import re
for threads in ([4] if '--threads4' in sys.argv else [3,2]):
 for lang in ['vi','en']:
  warm=bridge.request('tts',lang=lang,threads=threads,text='Xin chào.' if lang=='vi' else 'Hello.');(out/f'{lang}-{threads}-warmup.json').write_text(json.dumps(warm,indent=2))
  selected=[r for r in rows if r['target_lang']==lang]
  if '--smoke' in sys.argv:selected=selected[:3]
  path=out/f'{lang}-{threads}.jsonl';previous=[json.loads(x) for x in path.read_text().splitlines()] if path.exists() else [];done={r['id'] for r in previous}
  with path.open('a') as f:
   for i,row in enumerate(selected):
    if row['id'] in done:continue
    text=re.split(r'(?<=[.!?])\s+',row['reference_target'],maxsplit=1)[0]
    result=bridge.request('tts',lang=lang,threads=threads,text=text);result.update(id=row['id'],text=text,scope='first sentence of BTC reference; TTS synthesis/callback only, no speaker/acoustic latency')
    f.write(json.dumps(result,ensure_ascii=False)+'\n');f.flush()
    if (i+1)%20==0:print('TTS_QDC',lang,threads,i+1,flush=True)
bridge.close();summary={}
for path in out.glob('*.jsonl'):
 data=[json.loads(x) for x in path.read_text().splitlines()];summary[path.stem]=dict(n=len(data),generate_p50_ms=float(np.median([r['generate_ms'] for r in data])),generate_p95_ms=float(np.percentile([r['generate_ms'] for r in data],95)),callback_p50_ms=float(np.median([r['first_callback_ms'] for r in data])),callback_p95_ms=float(np.percentile([r['first_callback_ms'] for r in data],95)),empty=sum(r['samples']==0 for r in data))
(out/'summary.json').write_text(json.dumps(summary,indent=2));print(json.dumps(summary,indent=2),flush=True)
