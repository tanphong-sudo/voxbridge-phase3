import json,sys
from pathlib import Path
import onnx
root=Path(sys.argv[1]);out=root/'envit5-repaired';out.mkdir(exist_ok=True);(out/'models').mkdir(exist_ok=True)
for name in ['tokenizers','baseline','asr-inputs','mt200.jsonl','qdcbench-debug.apk','apk.sha256']:
 p=out/name
 if not p.exists():p.symlink_to(root/name)
for name,target in [('envit5',root/'models/envit5'),('envit5-stable',root/'envit5-stable-models/envit5-stable')]:
 p=out/'models'/name
 if not p.exists():p.symlink_to(target)
original=json.loads((root/'models/models.json').read_text());fixed=json.loads((root/'envit5-stable-models/models.json').read_text())['envit5-stable/encoder']
path=root/'envit5-stable-models'/fixed['onnx'];graph=onnx.load(str(path),load_external_data=False)
fixed['io']={k:{x.name:dict(shape=[d.dim_value for d in x.type.tensor_type.shape.dim],dtype=onnx.TensorProto.DataType.Name(x.type.tensor_type.elem_type)) for x in items} for k,items in [('inputs',graph.graph.input),('outputs',graph.graph.output)]}
manifest={'envit5/encoder':fixed,'envit5/decoder':original['envit5/decoder']}
(out/'models/models.json').write_text(json.dumps(manifest,indent=2)+'\n');print('REPAIRED_BUNDLE_READY')
