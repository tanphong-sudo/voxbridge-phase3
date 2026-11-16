#!/usr/bin/env python3
"""Install/verify QNN files and expose authenticated bridge through ADB; no QDC booking."""
import argparse,hashlib,json,os,secrets,subprocess,shlex
from pathlib import Path

def main():
    p=argparse.ArgumentParser();p.add_argument('--bundle',type=Path,required=True);p.add_argument('--adb',default='adb');p.add_argument('--serial',required=True)
    p.add_argument('--models',nargs='*',help='Only selected model prefixes');p.add_argument('--push-only',action='store_true');p.add_argument('--token-file',type=Path,required=True);p.add_argument('--port',type=int,default=18765);p.add_argument('--connect',action='store_true',help='adb connect supplied serial first')
    a=p.parse_args();bundle=a.bundle.resolve()
    def adb(*args,capture=False):
        return subprocess.run([a.adb,'-s',a.serial,*map(str,args)],check=True,text=True,capture_output=capture)
    if a.connect:subprocess.run([a.adb,'connect',a.serial],check=True)
    info={k:adb('shell','getprop',k,capture=True).stdout.strip() for k in ['ro.product.model','ro.soc.model','ro.build.version.release','ro.product.cpu.abi']}
    print(json.dumps(info,indent=2))
    if info['ro.product.cpu.abi']!='arm64-v8a':raise RuntimeError('An ARM64 Snapdragon device is required')
    if 'SM8650' not in info['ro.soc.model'].upper():raise RuntimeError('Artifacts target SM8650 (Snapdragon8Gen3); recompile for this device before running')
    apk=bundle/'qdcbench-release.apk'
    expected=(bundle/'apk.sha256').read_text().split()[0]
    if hashlib.sha256(apk.read_bytes()).hexdigest()!=expected:raise RuntimeError('APK hash mismatch')
    if not a.push_only:adb('install','-r',apk)
    base='/sdcard/Android/data/com.voxbridge.qdcbench/files'
    models=json.loads((bundle/'models/models.json').read_text())
    if a.models:models={k:v for k,v in models.items() if k.split('/')[0] in a.models}
    for name,entry in sorted(models.items(),key=lambda item:(not item[0].startswith("phowhisper"),not item[0].startswith("envit5"),item[0])):
        for row in entry['files']:
            source=bundle/'models'/row['path'];dest=base+'/models/'+row['path']
            if source.stat().st_size!=row['bytes']:raise RuntimeError('Model size changed: '+str(source))
            digest=hashlib.sha256()
            with source.open('rb') as f:
                while block:=f.read(1024*1024):digest.update(block)
            if digest.hexdigest()!=row['sha256']:raise RuntimeError('Model SHA changed: '+str(source))
            adb('shell','mkdir -p '+shlex.quote(str(Path(dest).parent)))
            existing=adb('shell','if [ -f '+shlex.quote(dest)+' ]; then sha256sum '+shlex.quote(dest)+'; fi',capture=True).stdout.split()
            if not existing or existing[0]!=row['sha256']:
                adb('push',source,dest)
                actual=adb('shell','sha256sum '+shlex.quote(dest),capture=True).stdout.split()
                if not actual or actual[0]!=row['sha256']:raise RuntimeError('Device hash mismatch: '+dest)
        print('DEVICE_VERIFIED',name,flush=True)
    if a.push_only:
        print('SELECTED_MODELS_PUSHED',flush=True);return
    # QDC root ADB creates root-owned external directories; assign only this package's model tree.
    # Normal non-root ADB devices already create an app-readable tree.
    if adb('shell','id -u',capture=True).stdout.strip()=='0':
        uid=adb('shell','stat -c %u /data/user/0/com.voxbridge.qdcbench',capture=True).stdout.strip()
        if not uid.isdigit():raise RuntimeError('Cannot resolve benchmark package UID')
        adb('shell','chown -R '+uid+':'+uid+' /data/media/0/Android/data/com.voxbridge.qdcbench/files/models')
        adb('shell','restorecon -RF /data/media/0/Android/data/com.voxbridge.qdcbench/files/models')
    token=secrets.token_hex(24);a.token_file.parent.mkdir(parents=True,exist_ok=True)
    fd=os.open(a.token_file,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
    with os.fdopen(fd,'w') as f:f.write(token+'\n')
    os.chmod(a.token_file,0o600)
    adb('shell','am','force-stop','com.voxbridge.qdcbench')
    adb('shell','cmd','package','compile','-m','speed','-f','com.voxbridge.qdcbench')
    # Capture output so the one-session token is not echoed into terminal logs.
    adb('shell','am','start','-n','com.voxbridge.qdcbench/.BenchActivity','--es','token',token,capture=True)
    adb('forward','tcp:'+str(a.port),'tcp:8765')
    print('DEVICE_PREPARED. Keep benchmark screen open; run run.py with the same token file.')
if __name__=='__main__':main()
