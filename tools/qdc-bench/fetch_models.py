#!/usr/bin/env python3
"""Download exact successful QNN context bundles. Run on VPS; auth on stdin, never disk."""
import argparse,hashlib,json,sys
from pathlib import Path
from protocol import safe_extract

def main():
    p=argparse.ArgumentParser();p.add_argument('--jobs',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
    import qai_hub as hub
    from qai_hub.client import ClientConfig
    client=hub.Client(ClientConfig(**json.loads(sys.stdin.readline())))
    a.out.mkdir(parents=True,exist_ok=True);ledger={};manifest=a.out/'models.json'
    if manifest.exists():ledger=json.loads(manifest.read_text())
    for name,row in json.loads(a.jobs.read_text()).items():
        if name in ledger:continue
        job=client.get_job(row['job_id'])
        if not job.get_status().success:raise RuntimeError('Not ready: '+name)
        folder=a.out/name;folder.mkdir(parents=True,exist_ok=True)
        model=job.get_target_model();download=Path(model.download(str(folder/'artifact')))
        if download.suffix=='.zip':safe_extract(download,folder/'context')
        else:raise RuntimeError('Expected precompiled QNN ONNX zip, got '+str(download))
        wrappers=list((folder/'context').rglob('*.onnx'))
        if len(wrappers)!=1:raise RuntimeError('Expected one ONNX wrapper: '+str(wrappers))
        files=[]
        for file in sorted((folder/'context').rglob('*')):
            if file.is_file():
                digest=hashlib.sha256()
                with file.open('rb') as f:
                    while block:=f.read(1024*1024):digest.update(block)
                files.append(dict(path=str(file.relative_to(a.out)),bytes=file.stat().st_size,sha256=digest.hexdigest()))
        ledger[name]=dict(job_id=row['job_id'],model_id=model.model_id,onnx=str(wrappers[0].relative_to(a.out)),files=files,
                          precision=row['precision'],qairt='2.50',target='Samsung Galaxy S24 Ultra / SM8650',outputs=row['outputs'])
        temp=manifest.with_suffix('.tmp');temp.write_text(json.dumps(ledger,indent=2)+'\n');temp.replace(manifest)
        print('READY',name,sum(f['bytes'] for f in files),flush=True)
    print('MODELS_READY',len(ledger),flush=True)
if __name__=='__main__':main()
