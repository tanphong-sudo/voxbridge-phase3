"""Repair encoder RMSNorm FP16 overflow; preserve original weights/model/IO and CPU parity."""
import json,sys,gc,shutil
from pathlib import Path
import torch,numpy as np,onnx,onnxruntime as ort
from transformers import AutoModelForSeq2SeqLM,AutoTokenizer
from static_mt import StaticEncoder
from stable_rms import replace_norms
root=Path(sys.argv[1]);dest=root/'envit5-stable-encoder';dest.mkdir(exist_ok=True)
source=root/'hf-cache/models--VietAI--envit5-translation/snapshots/840bc88104d5a4277af740eaedb024df8c3093e7'
torch.set_num_threads(4)
model=AutoModelForSeq2SeqLM.from_pretrained(source,local_files_only=True,use_safetensors=False,attn_implementation='eager').eval()
tok=AutoTokenizer.from_pretrained(root/'qdc-bench/tokenizers/envit5',local_files_only=True)
rows=[json.loads(x) for x in (root/'data/punctuation-btc200.jsonl').read_text().splitlines()];enc=tok(rows[0]['source_lang']+': '+rows[0]['source_text'],return_tensors='pt',padding='max_length',max_length=64)
encoder=StaticEncoder(model,'envit5').eval();ids=enc['input_ids'].to(torch.int32);mask=enc['attention_mask'].to(torch.int32)
with torch.inference_mode():expected=encoder(ids,mask)
count=replace_norms(model.encoder)
with torch.inference_mode():actual=encoder(ids,mask)
for a,b in zip(actual,expected):torch.testing.assert_close(a,b,rtol=5e-4,atol=5e-4)
outputs=['encoder_hidden_states']+[f'cross_{i}_{k}' for i in range(12) for k in ['k','v']]
with torch.inference_mode():torch.onnx.export(encoder,(ids,mask),str(dest/'model.onnx'),input_names=['input_ids','attention_mask'],output_names=outputs,opset_version=17,dynamo=False)
m=onnx.load(str(dest/'model.onnx'))
for node in m.graph.node:
 for attr in node.attribute:
  if node.op_type=='Constant' and attr.type==onnx.AttributeProto.TENSOR:
   x=onnx.numpy_helper.to_array(attr.t)
   if x.size==1 and np.issubdtype(x.dtype,np.floating) and float(x.reshape(-1)[0]) < -1e30:attr.t.CopyFrom(onnx.numpy_helper.from_array(np.full_like(x,-10000.)))
if (dest/'model.data').exists():(dest/'model.data').unlink()
onnx.save_model(m,str(dest/'model.onnx'),save_as_external_data=True,all_tensors_to_one_file=True,location='model.data',size_threshold=0);del m;gc.collect()
opts=ort.SessionOptions();opts.intra_op_num_threads=3
original=ort.InferenceSession(str(root/'mt-static-v2/envit5/encoder/model.onnx'),sess_options=opts,providers=['CPUExecutionProvider']);fixed=ort.InferenceSession(str(dest/'model.onnx'),sess_options=opts,providers=['CPUExecutionProvider'])
errors=[]
for row in rows:
 inputs=tok(row['source_lang']+': '+row['source_text'],return_tensors='np',padding='max_length',max_length=64)
 feed={k:v.astype(np.int32) for k,v in inputs.items()};old=original.run(None,feed);new=fixed.run(None,feed)
 for a,b in zip(new,old):np.testing.assert_allclose(a,b,rtol=5e-3,atol=5e-3)
 errors.append(max(float(np.max(np.abs(a-b))) for a,b in zip(new,old)))
(dest/'parity.json').write_text(json.dumps(dict(n=len(rows),replaced_norms=count,max_abs=max(errors),note='Same original weights; algebraically identical RMSNorm with power-of-two scaling, CPU encoder output parity across BTC200; QNN retest pending'),indent=2))
print('STABLE_ENCODER_READY',len(rows),max(errors),flush=True)
