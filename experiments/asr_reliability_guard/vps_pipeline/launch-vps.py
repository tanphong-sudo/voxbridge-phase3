from pathlib import Path
from datetime import datetime,timezone
import argparse,hashlib,json,os,platform,subprocess,sys

base=Path(__file__).resolve().parent
parser=argparse.ArgumentParser()
parser.add_argument('mode',choices=['pilot','full'])
parser.add_argument('--label')
args=parser.parse_args()
if args.label: assert all(c.isalnum() or c=='-' for c in args.label)
out=base/(args.label or args.mode+'-envit5')
out.mkdir(exist_ok=False)
original=Path('/home/opc/voxbridge-mt3-20261113/full-audio-qnn-ready-20261114/manifest200.jsonl')
rows=[json.loads(line) for line in original.read_text().splitlines()]
assert len(rows)==len({r['id'] for r in rows})==200
def sha(path):
    value=hashlib.sha256()
    with Path(path).open('rb') as handle:
        for chunk in iter(lambda:handle.read(1024*1024),b''): value.update(chunk)
    return value.hexdigest()
for path,expected in json.loads((base/'compiled-source-sha256.json').read_text()).items():
    assert sha(base/path)==expected,'Source changed after compilation: '+path
for row in rows: assert sha(row['wav'])==row['sha256'],row['id']
if args.mode=='pilot':
    selected={'VE-0037','VE-8787','EV-0157','EV-0523','EV-2748','EV-4505'}
    rows=[r for r in rows if r['id'] in selected]
    assert len(rows)==6
manifest=out/'manifest.jsonl'
manifest.write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows))
models=Path('/home/opc/voxbridge-mt3-20261113/pipeline-current-models')
env=dict(os.environ)
for key in list(env):
    if key.startswith('VOXBRIDGE_') or key in ['JAVA_TOOL_OPTIONS','_JAVA_OPTIONS']:env.pop(key)
env.update(HF_HUB_OFFLINE='1',TRANSFORMERS_OFFLINE='1',TOKENIZERS_PARALLELISM='false',
    VOXBRIDGE_MT_CACHE='/home/opc/voxbridge-mt3-20261113/hf-cache',
    VOXBRIDGE_PARAKEET_PYTHON='/home/opc/en-asr-new3-20261113/nemo/venv/bin/python',
    VOXBRIDGE_PARAKEET_WORKER=str(base/'adapter/parakeet_jsonl_worker.py'))
command=['/usr/lib/jvm/java-17-openjdk/bin/java','-Xmx2g','-cp',(base/'classpath.txt').read_text(),
    'voxbridge.e2e.FullAudioRunnerKt',str(manifest),str(models),
    '/home/opc/voxbridge-mt3-20261113/venv/bin/python',
    '/home/opc/voxbridge-current/tools/phase3/audio_pipeline_worker.py','envit5',str(out)]
assert len(command[5:])==6
metadata=dict(mode=args.mode,expected_samples=len(rows),started_utc=datetime.now(timezone.utc).isoformat(),
    hostname=platform.node(),platform=platform.platform(),original_manifest=str(original),
    original_manifest_sha256=sha(original),selected_manifest_sha256=sha(manifest),command=command,
    asr={'vi':'existing PhoWhisper-small ONNX FP32 current Kotlin CPU','en':'existing frozen Parakeet TDT v2 NeMo FP32 native greedy_trim CPU'},
    mt='existing VPS EnViT5 pinned local HF snapshots / beam2 max192',
    tts='existing VPS Piper VI vais1000 / EN lessac / existing cached repeat prompts',
    guards='merged current local tentative-output feature onto newer VPS safety/punctuation/coverage core',
    draft_separate=True,memory='existing FullAudioRunner empty baseline',playback_performed=False,
    device_scope='VPS functional; no QNN parity, phone mic/speaker or Snapdragon latency claim',
    worker_sha256=sha('/home/opc/voxbridge-current/tools/phase3/audio_pipeline_worker.py'),
    asr_adapter_sha256=sha(base/'adapter/parakeet_jsonl_worker.py'))
(out/'metadata.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n')
print('RUN',out,'samples',len(rows),flush=True)
monitor=[sys.executable,str(base/'adapter/monitor_process.py'),'--rss-output',str(out/'memory-rss.csv'),
    '--summary-output',str(out/'process-summary.json'),'--sample-log',str(out/'results.jsonl'),
    '--interval-seconds','2','--timeout-seconds','10800','--',*command]
with (out/'console.log').open('w') as log:
    result=subprocess.run(monitor,env=env,stdout=log,stderr=subprocess.STDOUT,cwd=base)
actual=[json.loads(line) for line in (out/'results.jsonl').read_text().splitlines()] if (out/'results.jsonl').exists() else []
metadata.update(finished_utc=datetime.now(timezone.utc).isoformat(),exit_code=result.returncode,
    completed_samples=len(actual),unique_completed=len({r['id'] for r in actual}),
    changed_input_ids=[r['id'] for r in rows if sha(r['wav'])!=r['sha256']])
metadata['complete']=result.returncode==0 and len(actual)==len(rows) and {r['id'] for r in actual}=={r['id'] for r in rows} and not metadata['changed_input_ids']
(out/'metadata.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n')
print('FINISHED',out,'complete',metadata['complete'],'exit',result.returncode,flush=True)
raise SystemExit(result.returncode or not metadata['complete'])
