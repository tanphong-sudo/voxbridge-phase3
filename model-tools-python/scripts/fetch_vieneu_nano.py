#!/usr/bin/env python3
"""Fetch the frozen Phase-3 Nano candidate without committing weights or changing Piper."""
from pathlib import Path
import argparse,hashlib,json,shutil,urllib.request

REPO = 'pnnbao-ump/VieNeu-TTS-v3-Nano'
REVISION = 'aba295eb96a6fa6003ebe417cc1f2802a7adc1dc'
FILES = {'codec_decoder.onnx': (99319941, 'b0ab15e7828a39d53679e25b1ba4ba415a61311307202a6323130b9e1cc3029d'),
 'config.json': (2927, '3762f1716fd8d7a1451ddf2e051c03b0a341e8adda4f1dbd0db0ae9777514e56'),
 'constants.npz': (53448, '7c011938effe41687a9af85107a31a040dfcf0fb3d0f048ec10f4fb65c1a8829'),
 'duration_predictor.onnx': (727809, '20fd7fa60006d0a48ee82e0451b3f920d2052588c083c756cce669f3947c0a68'),
 'text_encoder.onnx': (26519943, '204f02cccae1f16ccb2d3840f05721a37fe250b82cbd456337a0ccb49615e4bf'),
 'vector_estimator.onnx': (155132418,
                           'c6c1d4398ca35d3ad1bd3f0459413d1b975d09e7f2f3a2b4493a7b92bbf6ce93')}

def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        while chunk:=f.read(1024*1024):h.update(chunk)
    return h.hexdigest()

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--out',type=Path,default=Path('models/text_to_speech/vieneu-nano'))
    p.add_argument('--cache',type=Path,help='Optional already-downloaded artifact directory')
    a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True);rows=[]
    for name,(size,sha) in FILES.items():
        dest=a.out/name
        if dest.exists():
            if dest.stat().st_size!=size or digest(dest)!=sha:raise RuntimeError('Existing artifact integrity mismatch: '+str(dest))
        else:
            part=dest.with_suffix(dest.suffix+'.part')
            if a.cache and (a.cache/name).is_file():shutil.copyfile(a.cache/name,part)
            else:
                with urllib.request.urlopen(f'https://huggingface.co/{REPO}/resolve/{REVISION}/{name}',timeout=60) as src,part.open('wb') as dst:
                    while chunk:=src.read(1024*1024):dst.write(chunk)
            if part.stat().st_size!=size or digest(part)!=sha:raise RuntimeError('Downloaded artifact integrity mismatch: '+name)
            part.replace(dest)
        rows.append(dict(path=name,bytes=size,sha256=sha))
    source=dict(repo=REPO,revision=REVISION,license_spdx='apache-2.0',model_card_url=f'https://huggingface.co/{REPO}/blob/{REVISION}/README.md',downloaded_files=rows,voice='Trúc Ly',steps=8,sdk='3.8.3',android_validated=False,qnn=False)
    temp=a.out/'source.json.part';temp.write_text(json.dumps(source,ensure_ascii=False,indent=2));temp.replace(a.out/'source.json')
    print('Verified Nano candidate:',sum(r['bytes'] for r in rows),'bytes;',a.out/'source.json')

if __name__=='__main__':main()
