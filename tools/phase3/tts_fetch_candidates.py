#!/usr/bin/env python3
"""Fetch the two pinned official Piper INT8 bundles into an experiment directory."""
import argparse,hashlib,json,tarfile,urllib.request
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--manifest',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
a.out.mkdir(parents=True,exist_ok=True)
for row in json.loads(a.manifest.read_text()):
 archive=a.out/row['name']
 if not archive.exists():
  with urllib.request.urlopen(row['browser_download_url'],timeout=60) as src,archive.open('wb') as dst:
   while chunk:=src.read(1024*1024):dst.write(chunk)
 assert archive.stat().st_size==row['size']
 assert 'sha256:'+hashlib.sha256(archive.read_bytes()).hexdigest()==row['digest']
 with tarfile.open(archive) as tar:
  members=tar.getmembers()
  for item in members:
   dest=(a.out/item.name).resolve()
   assert dest.is_relative_to(a.out.resolve()) and not item.issym() and not item.islnk()
  tar.extractall(a.out,members=members)
 print('Verified and extracted',row['name'],flush=True)
