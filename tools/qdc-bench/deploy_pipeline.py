#!/usr/bin/env python3
"""Verify and deploy integrated app/corpus, then launch one resumable 200-case candidate run."""
import argparse,hashlib,json,shlex,subprocess,time
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--bundle',type=Path,required=True);p.add_argument('--serial',required=True);p.add_argument('--adb',default='adb');p.add_argument('--mt',choices=['envit5','vinai'],default='envit5');p.add_argument('--limit',type=int,default=200);p.add_argument('--run-name',required=True);p.add_argument('--push-only',action='store_true');p.add_argument('--smoke',action='store_true');a=p.parse_args()
if not 1<=a.limit<=200 or not a.run_name.replace('-','').replace('_','').isalnum():p.error('Invalid limit/run name')
bundle=a.bundle.resolve();base='/sdcard/Android/data/com.voxbridge/files'
def adb(*args):return subprocess.run([a.adb,'-s',a.serial,*map(str,args)],check=True,capture_output=True,text=True).stdout.strip()
def sha(file):
 h=hashlib.sha256()
 with file.open('rb') as f:
  while b:=f.read(4*1024*1024):h.update(b)
 return h.hexdigest()
identity={k:adb('shell','getprop',k) for k in ['ro.product.model','ro.soc.model','ro.product.cpu.abi','ro.build.fingerprint']}
if identity['ro.product.cpu.abi']!='arm64-v8a' or 'SM8650' not in identity['ro.soc.model'].upper():raise RuntimeError('This bundle requires SM8650 ARM64; compile matching contexts for other chips')
apk=bundle/'voxbridge-qdc-pipeline.apk';assert sha(apk)==(bundle/'apk.sha256').read_text().split()[0]
# Validate every source before modifying the device.
root=bundle/'files/models';entries=[]
for line in (root/'integrity.sha256').read_text().splitlines():
 digest,size,relative=line.split(' ',2);file=(root/relative).resolve();assert file.is_relative_to(root.resolve()) and file.is_file() and file.stat().st_size==int(size) and sha(file)==digest
 entries.append((file,digest))
for row in map(json.loads,(bundle/'files/corpus/manifest200.jsonl').read_text().splitlines()):assert sha(bundle/'files'/row['wav'])==row['sha256']
adb('install','-r',apk);adb('shell','am','force-stop','com.voxbridge')
files=[f for f in (bundle/'files').rglob('*') if f.is_file() and not f.name.startswith('.')]
for index,file in enumerate(files):
 relative=file.relative_to(bundle/'files').as_posix();dest=base+'/'+relative;digest=sha(file)
 actual=adb('shell','if [ -f '+shlex.quote(dest)+' ]; then sha256sum '+shlex.quote(dest)+'; fi').split()
 if not actual or actual[0]!=digest:
  adb('shell','mkdir -p '+shlex.quote(str(Path(dest).parent)));adb('push',file,dest)
  if adb('shell','sha256sum '+shlex.quote(dest)).split()[0]!=digest:raise RuntimeError('Device checksum mismatch: '+relative)
 if index%100==0:print('VERIFIED_FILES',index+1,'/',len(files),flush=True)
if adb('shell','id','-u')=='0':
 uid=adb('shell','stat -c %u /data/user/0/com.voxbridge');assert uid.isdigit()
 adb('shell','chown -R '+uid+':'+uid+' /data/media/0/Android/data/com.voxbridge/files');adb('shell','restorecon -RF /data/media/0/Android/data/com.voxbridge/files')
out=bundle/'device-runs'/a.run_name;out.mkdir(parents=True,exist_ok=True)
(out/'identity.json').write_text(json.dumps(identity,indent=2));(out/'apk.sha256').write_text(sha(apk))
if a.push_only:print('PUSH_COMPLETE');raise SystemExit
for command,name in [(['shell','dumpsys','thermalservice'],'thermal-before.txt'),(['shell','dumpsys','meminfo','com.voxbridge'],'memory-before.txt')]:
 try:(out/name).write_text(adb(*command))
 except subprocess.CalledProcessError:pass
print(adb('shell','am','start','-n','com.voxbridge/.ui.PipelineBenchActivity','--es','manifest','corpus/smoke2.jsonl' if a.smoke else 'corpus/manifest200.jsonl','--es','output','results/'+a.run_name+'.jsonl','--es','mt',a.mt,'--ei','limit',2 if a.smoke else a.limit))
print('RUNNING',a.run_name,'collect using: adb -s',a.serial,'pull',base+'/results',str(out),flush=True)
