#!/usr/bin/env python3
"""Start/resume one existing AI Hub experiment worker, credentials on encrypted stdin only."""
import argparse,configparser,json,shlex,subprocess
from pathlib import Path

ROOT='/home/opc/voxbridge-mt3-20261113'
GROUPS={
 'mask':('run_mt_tests.py','aihub-mask-inputs','aihub-mask-exports','aihub-mask-exports/followups.json','aihub-mask-tests'),
 'mt':('run_mt_tests.py','aihub-all-inputs','aihub-mt-20261113','tools/followups.json','aihub-all-tests'),
 'aux':('run_mt_tests.py','aihub-aux-inputs','aihub-aux-supported','aihub-aux-exports/followups.json','aihub-aux-tests'),
 'pho':('run_mt_tests.py','aihub-pho-inputs/inputs','aihub-pho-inputs/registered','tools/followups.json','aihub-pho-tests'),
 'repaired':('run_mt_tests.py','aihub-aux-inputs','aihub-aux-repaired','aihub-aux-exports/followups.json','aihub-aux-repaired-tests'),
 'w8a8':('run_w8a8_tests.py','aihub-w8a8-inputs','aihub-w8a8-exports','aihub-w8a8-exports/followups.json','aihub-w8a8-tests'),
}

def main():
 p=argparse.ArgumentParser();p.add_argument('group',choices=[*GROUPS,'links','collect','download']);p.add_argument('--passes',type=int,default=240);a=p.parse_args()
 cfg=configparser.ConfigParser();cfg.read(Path.home()/'.qai_hub/client.ini')
 selected=next(cfg[s] for s in cfg.sections() if cfg[s].get('api_token'))
 secret={k:selected[k] for k in ['api_token','api_url','web_url'] if selected.get(k)}
 if a.group in ['collect','download']:
  script='collect_all_results.py' if a.group=='collect' else 'download_artifacts.py'
  out='aihub-collected' if a.group=='collect' else 'aihub-artifacts'
  args=[ROOT+'/venv/bin/python','-u',ROOT+'/tools/'+script,'--root',ROOT,'--out',ROOT+'/'+out,'--passes',str(a.passes)]
  log=ROOT+('/aihub-collect.log' if a.group=='collect' else '/aihub-downloads.log')
 elif a.group=='links':
  args=[ROOT+'/venv/bin/python','-u',ROOT+'/tools/run_links.py','--root',ROOT,'--out',ROOT+'/aihub-links','--passes',str(a.passes)]
  log=ROOT+'/aihub-links.log'
 else:
  script,inputs,exports,follow,out=GROUPS[a.group]
  args=[ROOT+'/venv/bin/python','-u',ROOT+'/tools/'+script,'--inputs',ROOT+'/'+inputs,'--exports',ROOT+'/'+exports,
   '--followups',ROOT+'/'+follow,'--out',ROOT+'/'+out,'--passes',str(a.passes)]
  log=ROOT+'/'+out+'.log'
 command=' '.join(shlex.quote(x) for x in args)+' >> '+shlex.quote(log)+' 2>&1'
 result=subprocess.run(['ssh','-i',str(Path.home()/'<ssh-key>'),'-o','IdentitiesOnly=yes','-o','BatchMode=yes','opc@<vps-host>',command],input=json.dumps(secret)+'\n',text=True)
 print(a.group,'worker exit',result.returncode)
if __name__=='__main__':main()
