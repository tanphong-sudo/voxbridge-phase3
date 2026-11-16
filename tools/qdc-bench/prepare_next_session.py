#!/usr/bin/env python3
"""Materialize a checksum-verified standalone bundle; never claims full app readiness."""
import argparse,hashlib,json,shutil
from pathlib import Path

def digest(path):
 h=hashlib.sha256()
 with path.open('rb') as f:
  while b:=f.read(4*1024*1024):h.update(b)
 return h.hexdigest()

def child(root,relative):
 p=(root/relative).resolve()
 if not p.is_relative_to(root.resolve()):raise ValueError('Path outside bundle: '+str(relative))
 return p

def checked_copy(source_root,dest_root,row):
 source=child(source_root,row['path']);dest=child(dest_root,row['path'])
 if not source.is_file() or source.stat().st_size!=row['bytes'] or digest(source)!=row['sha256']:
  raise ValueError('Source integrity failure: '+row['path'])
 dest.parent.mkdir(parents=True,exist_ok=True)
 if not dest.is_file() or dest.stat().st_size!=row['bytes'] or digest(dest)!=row['sha256']:
  shutil.copyfile(source,dest)
 if digest(dest)!=row['sha256']:raise ValueError('Destination integrity failure: '+row['path'])

def prepare(source,fixed,apk,host,out):
 if source.resolve()==out.resolve():raise ValueError('Destination must differ from source')
 original=json.loads((source/'models/models.json').read_text())
 repaired=json.loads((fixed/'models.json').read_text())['envit5-stable/encoder']
 models=dict(original);models['envit5/encoder']=repaired
 out.mkdir(parents=True,exist_ok=True)
 for name,entry in models.items():
  src=fixed if name=='envit5/encoder' else source/'models'
  for row in entry['files']:checked_copy(src,out/'models',row)
  if not child(out/'models',entry['onnx']).is_file():raise ValueError('Missing wrapper: '+name)
 (out/'models/models.json').write_text(json.dumps(models,indent=2)+'\n')
 for name in ['tokenizers','baseline','asr-inputs']:
  shutil.copytree(source/name,out/name,dirs_exist_ok=True)
 shutil.copyfile(source/'mt200.jsonl',out/'mt200.jsonl')
 shutil.copytree(host,out/'host',dirs_exist_ok=True,ignore=shutil.ignore_patterns('__pycache__','._*'))
 shutil.copyfile(apk,out/'qdcbench-release.apk')
 (out/'apk.sha256').write_text(digest(out/'qdcbench-release.apk')+'  qdcbench-release.apk\n')
 readiness={'standalone_models_checksum_verified':True,'full_pipeline_ready':False,
  'scope':'ASR/MT standalone QNN benchmark only; not the integrated release-to-speech test',
  'target':'SM8650','qairt':'2.50','reuse_buffers_default':True,
  'envit5_encoder_job':repaired['job_id'],
  'blockers':['Parakeet v2 QNN export/runtime not validated','Product app translator is ML Kit',
              'Full Piper QNN frontend/vocoder not integrated','Integrated phone first-audio latency not measured']}
 (out/'READINESS.json').write_text(json.dumps(readiness,indent=2)+'\n')
 return readiness

def main():
 p=argparse.ArgumentParser(description=__doc__)
 for n in ['source','fixed','apk','host','out']:p.add_argument('--'+n,type=Path,required=True)
 a=p.parse_args();print(json.dumps(prepare(a.source,a.fixed,a.apk,a.host,a.out),indent=2))
if __name__=='__main__':main()
