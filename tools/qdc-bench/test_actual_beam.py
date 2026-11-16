"""Independently drive Java beam2 with original CPU graph logits and match full HF tokens."""
import sys,json,subprocess,struct
from pathlib import Path
import numpy as np
import torch
from transformers import AutoConfig,GenerationConfig,AutoTokenizer
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'model-tools-python/qaihub'))
from ort_static_mt import OrtStaticMt
import onnxruntime as ort
root=Path(sys.argv[1]);classdir=sys.argv[2];torch.set_num_threads(3);report=[]
for model in ['vinai-vi2en','vinai-en2vi','envit5']:
 base=root/'mt-static-v2'/model;opts=ort.SessionOptions();opts.intra_op_num_threads=3
 encoder,decoder=[ort.InferenceSession(str(base/part/'model.onnx'),sess_options=opts,providers=['CPUExecutionProvider']) for part in ['encoder','decoder']]
 runtime=OrtStaticMt(AutoConfig.from_pretrained(base/'tokenizer'),encoder,decoder,GenerationConfig.from_pretrained(base/'tokenizer'));tok=AutoTokenizer.from_pretrained(base/'tokenizer')
 original=runtime.forward
 baseline=[json.loads(x) for x in (root/'mt-static-v2-results/fp32'/model/'results.jsonl').read_text().splitlines()]
 for row in (baseline if '--all' in sys.argv else [baseline[0],baseline[1],max(baseline,key=lambda x:len(x['tokens']))]):
  src=row['source_lang'];vi=model!='envit5'
  if vi:tok.src_lang='vi_VN' if src=='vi' else 'en_XX'
  start=tok.lang_code_to_id['en_XX' if src=='vi' else 'vi_VN'] if vi else 0
  eos=2 if vi else 1;forced=2 if vi else -1
  proc=subprocess.Popen(['java','-cp',classdir,'BeamCli',str(start),str(eos),'192',str(vi).lower(),str(forced)],stdin=subprocess.PIPE,stdout=subprocess.PIPE)
  state=dict(final=None,step=0,expected_parents=None)
  def read(n):
   data=proc.stdout.read(n)
   if len(data)!=n:raise RuntimeError('Beam CLI ended')
   return data
  def forward(*args,**kwargs):
   ids=kwargs['decoder_input_ids'];actual_ids=struct.unpack('>2i',read(8));parents=struct.unpack('>2i',read(8))
   assert list(actual_ids)==ids.reshape(-1).tolist(),(model,row['id'],state['step'],actual_ids,ids.tolist())
   past=kwargs.get('past_key_values')
   if past is not None and state['expected_parents'] is not None:assert parents==state['expected_parents']
   output=original(*args,**kwargs);values=output.logits.numpy().astype('>f4')
   proc.stdin.write(struct.pack('>i',values.size)+values.tobytes());proc.stdin.flush();done=read(1)!=b'\x00'
   if done:
    count=struct.unpack('>i',read(4))[0];state['final']=list(struct.unpack('>'+str(count)+'i',read(count*4)))
   state['step']+=1;return output
  runtime.forward=forward
  text=src+': '+row['source'] if model=='envit5' else row['source'];encoded=tok(text,return_tensors='pt',padding='max_length',max_length=64)
  kwargs=dict(num_beams=2,max_new_tokens=192,do_sample=False)
  if vi:kwargs.update(decoder_start_token_id=start,forced_bos_token_id=None,early_stopping=True)
  with torch.inference_mode():actual=runtime.generate(**encoded,**kwargs)[0].tolist()
  assert actual==state['final']==row['tokens'],(model,row['id'],actual,state['final'],row['tokens'])
  proc.wait(timeout=5);report.append(dict(model=model,id=row['id'],tokens=len(actual),exact=True));print('ACTUAL_BEAM_PARITY',report[-1],flush=True)
  runtime.forward=original
Path(sys.argv[3]).write_text(json.dumps(report,indent=2)+'\n')
