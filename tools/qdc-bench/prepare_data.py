#!/usr/bin/env python3
"""Freeze host tokenizers/data and exact context IO contracts, without copying source model weights."""
import argparse,json,shutil,hashlib
from pathlib import Path

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
    import onnx
    a.out.mkdir(parents=True,exist_ok=True)
    root=a.root
    for model in ['envit5','vinai-vi2en','vinai-en2vi']:
        dest=a.out/'tokenizers'/model
        shutil.copytree(root/'mt-static-v2'/model/'tokenizer',dest,dirs_exist_ok=True)
        ref=a.out/'baseline'/model;ref.mkdir(parents=True,exist_ok=True)
        for name in ['results.jsonl','summary.json']:shutil.copy2(root/'mt-static-v2-results/fp32'/model/name,ref/name)
    selected=json.loads((root/'phowhisper-small-selected/source.json').read_text());snap=Path(selected['snapshot'])
    dest=a.out/'tokenizers/phowhisper-small';dest.mkdir(parents=True,exist_ok=True)
    for file in snap.iterdir():
        if file.suffix in ['.json','.txt','.model'] and not file.name.endswith('.index.json'):shutil.copy2(file,dest/file.name)
    shutil.copy2(root/'data/punctuation-btc200.jsonl',a.out/'mt200.jsonl')
    models=json.loads((a.out/'models/models.json').read_text())
    for name,row in models.items():
        path=a.out/'models'/row['onnx'];graph=onnx.load(str(path),load_external_data=False)
        spec={}
        for key,items in [('inputs',graph.graph.input),('outputs',graph.graph.output)]:
            spec[key]={x.name:dict(shape=[d.dim_value for d in x.type.tensor_type.shape.dim],dtype=onnx.TensorProto.DataType.Name(x.type.tensor_type.elem_type)) for x in items}
        row['io']=spec
    (a.out/'models/models.json').write_text(json.dumps(models,indent=2)+'\n')
    print('DATA_READY: MT200, original tokenizers/generation config, 8 exact context specs',flush=True)
if __name__=='__main__':main()
