"""Keep Piper weights unchanged; mask padded activations at every convolution boundary."""
import argparse,json
from pathlib import Path
import numpy as np,onnx,onnxruntime as ort
from onnx import helper,numpy_helper,TensorProto
p=argparse.ArgumentParser();p.add_argument('--root',required=True,type=Path);a=p.parse_args();report={}
for lang in ['vi','en']:
 source=a.root/'piper-qnn-retry-rank4'/lang/'vocoder64/model.onnx';m=onnx.load(str(source));m=onnx.shape_inference.infer_shapes(m,strict_mode=True)
 infos={v.name:v for v in [*m.graph.input,*m.graph.output,*m.graph.value_info]};nodes=[];masks={}
 m.graph.input.append(helper.make_tensor_value_info('valid_frames',TensorProto.INT32,[1]))
 for node in m.graph.node:
  if node.op_type not in ['Conv','ConvTranspose']:
   nodes.append(node);continue
  value=node.output[0];info=infos.get(value)
  if info is None:raise ValueError('No inferred convolution shape '+value)
  dims=[d.dim_value for d in info.type.tensor_type.shape.dim]
  if len(dims)!=3 or dims[-1]<64 or dims[-1]%64:raise ValueError(('Unexpected convolution shape',dims))
  time=dims[-1];factor=time//64
  if time not in masks:
   prefix='padding_'+str(time);index=prefix+'_index';scale=prefix+'_scale';limit=prefix+'_limit';boolean=prefix+'_bool';mask=prefix+'_float'
   m.graph.initializer.extend([numpy_helper.from_array(np.arange(time,dtype=np.int32).reshape(1,1,time),index),numpy_helper.from_array(np.array([factor],dtype=np.int32),scale)])
   nodes.extend([helper.make_node('Mul',['valid_frames',scale],[limit]),helper.make_node('Less',[index,limit],[boolean]),helper.make_node('Cast',[boolean],[mask],to=TensorProto.FLOAT)])
   masks[time]=mask
  node.output[0]=value+'_unmasked';nodes.append(node);nodes.append(helper.make_node('Mul',[node.output[0],masks[time]],[value],name='Mask_'+str(len(nodes))))
 del m.graph.node[:];m.graph.node.extend(nodes);dest=a.root/'piper-masked64'/lang;dest.mkdir(parents=True,exist_ok=True);onnx.save(m,str(dest/'model.onnx'));onnx.checker.check_model(str(dest/'model.onnx'),full_check=True)
 opt=ort.SessionOptions();opt.intra_op_num_threads=3
 session=ort.InferenceSession(str(dest/'model.onnx'),sess_options=opt,providers=['CPUExecutionProvider']);dynamic=ort.InferenceSession(str(a.root/'piper-qnn-retry-rank4'/lang/'vocoder-dynamic.onnx'),sess_options=opt,providers=['CPUExecutionProvider']);name=dynamic.get_inputs()[0].name
 fixture=np.load(a.root/'piper-hybrid-front'/lang/'bridge-proof.npz')['latent'];rows=[]
 for size in [1,2,8,16,32,48,63,64]:
  data=fixture[:,:,:size];expected=dynamic.run(None,{name:data})[0].reshape(-1)
  actual=session.run(None,{name:np.pad(data,[(0,0),(0,0),(0,64-size)]),'valid_frames':np.array([size],dtype=np.int32)})[0].reshape(-1)[:size*256]
  np.testing.assert_allclose(actual,expected,rtol=1e-5,atol=1e-5);rows.append({'frames':size,'max_abs':float(np.abs(actual-expected).max())})
 report[lang]=rows;print('MASKED_VOCODER_PARITY',lang,rows,flush=True)
(a.root/'piper-masked64/parity.json').write_text(json.dumps(report,indent=2))
