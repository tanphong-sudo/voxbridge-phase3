"""Check a phonemizer-preserving bridge: sherpa frontend latent output -> unchanged CPU vocoder."""
import sys,json
from pathlib import Path
import numpy as np,onnx,onnxruntime as ort,sherpa_onnx
sys.path.insert(0,'/home/opc/voxbridge-current/model-tools-python/qaihub')
from retry_piper_split import cut
root=Path('/home/opc/voxbridge-mt3-20261113');out=root/'piper-hybrid-front';out.mkdir(exist_ok=True);summary={}
for lang,text in [('vi','Dừng máy số năm, không phải máy số ba.'),('en','Stop machine five, not machine three.')]:
 voice='vi_VN-vais1000-medium' if lang=='vi' else 'en_US-lessac-medium';bundle=Path('/home/opc/voxbridge/models/text_to_speech/piper/vits-piper-'+voice);source=bundle/(voice+'.onnx');m=onnx.load(str(source));latent=next(n for n in m.graph.node if n.name=='/dec/conv_pre/Conv').input[0]
 front=cut(m,[x.name for x in m.graph.input],[latent],{latent:[1,192,None]});front.graph.initializer.append(onnx.numpy_helper.from_array(np.array([1,1,1,-1],dtype=np.int64),'latent_flat_shape'));front.graph.node.append(onnx.helper.make_node('Reshape',[latent,'latent_flat_shape'],['output'],name='LatentTransport'))
 del front.graph.output[:];front.graph.output.append(onnx.helper.make_tensor_value_info('output',onnx.TensorProto.FLOAT,[1,1,1,None]));metadata={x.key:x.value for x in m.metadata_props}
 for key,value in metadata.items():entry=front.metadata_props.add();entry.key=key;entry.value=value
 folder=out/lang;folder.mkdir(exist_ok=True);file=folder/'frontend.onnx';onnx.save(front,str(file));onnx.checker.check_model(str(file))
 def engine(path):return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(path),tokens=str(bundle/'tokens.txt'),data_dir=str(bundle/'espeak-ng-data'),noise_scale=0.,noise_scale_w=0.,length_scale=1.),num_threads=3),silence_scale=1.0))
 full=engine(source);frontend=engine(file);generation=sherpa_onnx.GenerationConfig();generation.sid=0;generation.speed=1.;generation.silence_scale=1.
 expected=np.asarray(full.generate(text,generation).samples);latent_values=np.asarray(frontend.generate(text,generation).samples,dtype=np.float32);assert latent_values.size%192==0,latent_values.shape
 np.savez(folder/'bridge-proof.npz',latent=latent_values.reshape(1,192,-1),expected=expected)
 opts=ort.SessionOptions();opts.intra_op_num_threads=3;s=ort.InferenceSession(str(root/'piper-qnn-retry-rank4'/lang/'vocoder-dynamic.onnx'),sess_options=opts,providers=['CPUExecutionProvider']);audio=s.run(None,{latent:latent_values.reshape(1,192,-1)})[0].reshape(-1)
 np.testing.assert_allclose(audio,expected,rtol=1e-5,atol=1e-5);summary[lang]=dict(samples=len(audio),frames=len(latent_values)//192,exact=bool(np.array_equal(audio,expected)),max_abs=float(np.max(np.abs(audio-expected))));print('HYBRID_FRONT_PARITY',lang,summary[lang],flush=True)
(out/'parity.json').write_text(json.dumps(summary,indent=2))
