"""Wait for current QDC benchmark, then smoke/gate/full-test repaired EnViT5. No busy polling."""
import os,sys,time,subprocess,json
from pathlib import Path
pid=int(sys.argv[1]);root=Path('/home/opc/voxbridge-mt3-20261113/qdc-bench');python='/home/opc/voxbridge-mt3-20261113/venv/bin/python'
while True:
 try:os.kill(pid,0)
 except ProcessLookupError:break
 time.sleep(30)
common=[python,str(root/'host/run.py'),'--bundle',str(root/'envit5-repaired'),'--models','envit5','--port','28900','--token-file',str(root/'.session-token')]
smoke=root/'qdc-envit5-repaired-smoke';full=root/'qdc-envit5-repaired-btc'
def score(folder):
 subprocess.run([python,str(root/'host/score.py'),'--results',str(folder),'--bundle',str(root/'envit5-repaired')],check=True)
 return json.loads((folder/'summary.json').read_text())['scores']['envit5']
print('START_REPAIRED_SMOKE',flush=True)
subprocess.run(common+['--out',str(smoke),'--limit','2'],check=True);s=score(smoke)
if s['n']!=2 or s['token_caps'] or s['blank'] or s['chrf']<s['fp32_chrf_same_subset']-5:
 print('REPAIRED_SMOKE_REJECTED',json.dumps(s),flush=True);raise SystemExit(2)
print('START_REPAIRED_FULL_BTC200',flush=True)
subprocess.run(common+['--out',str(full)],check=True);s=score(full);print('REPAIRED_BTC200_FINISHED',json.dumps(s),flush=True)
