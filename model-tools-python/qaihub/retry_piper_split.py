"""Extract Piper vocoder (static frames) after the dynamic duration/flow frontend. Preserve weights."""
import json,sys,hashlib
from pathlib import Path
import numpy as np,onnx,onnxruntime as ort

def cut(model,inputs,outputs,shapes):
    producer={o:n for n in model.graph.node for o in n.output};initial={i.name:i for i in model.graph.initializer};needed=set(outputs);nodes=set();stack=list(outputs)
    while stack:
        name=stack.pop()
        if name in inputs or name in initial:continue
        node=producer.get(name)
        if node is None:raise ValueError('Missing producer '+name)
        if node.name in nodes:continue
        nodes.add(node.name)
        for x in node.input:
            if x and x not in needed:needed.add(x);stack.append(x)
    infos={x.name:x for x in [*model.graph.input,*model.graph.output,*model.graph.value_info]}
    def info(name):return onnx.helper.make_tensor_value_info(name,onnx.TensorProto.FLOAT,shapes[name]) if name in shapes else infos[name]
    graph=onnx.helper.make_graph([n for n in model.graph.node if n.name in nodes],'Piper_split',[info(x) for x in inputs],[info(x) for x in outputs],[x for k,x in initial.items() if k in needed])
    result=onnx.helper.make_model(graph,opset_imports=list(model.opset_import));result.ir_version=model.ir_version;return result

def main():
    root=Path(sys.argv[1]);out=root/'piper-qnn-retry-rank4';out.mkdir(exist_ok=True);ledger={}
    opts=ort.SessionOptions();opts.intra_op_num_threads=3
    for lang in ['vi','en']:
        voice='vi_VN-vais1000-medium' if lang=='vi' else 'en_US-lessac-medium'
        source=Path('/home/opc/voxbridge/models/text_to_speech/piper/vits-piper-'+voice)/(voice+'.onnx');model=onnx.load(str(source))
        node=next(n for n in model.graph.node if n.name=='/dec/conv_pre/Conv');latent=node.input[0];weights=next(i for i in model.graph.initializer if i.name==node.input[1]);channels=weights.dims[1]
        frames=64;outputs=[x.name for x in model.graph.output];vocoder=cut(model,[latent],outputs,{latent:[1,channels,frames],outputs[0]:[1,1,1,frames*256]})
        dest=out/lang/'vocoder64';dest.mkdir(parents=True,exist_ok=True);file=dest/'model.onnx'
        
        if (dest/'model.data').exists():(dest/'model.data').unlink()
        onnx.save_model(vocoder,str(file),save_as_external_data=True,all_tensors_to_one_file=True,location='model.data',size_threshold=1024);onnx.checker.check_model(str(file),full_check=True)
        # Real latent from the unchanged original model, not random calibration.
        extended=onnx.ModelProto();extended.CopyFrom(model);extended.graph.output.append(onnx.helper.make_tensor_value_info(latent,onnx.TensorProto.FLOAT,[1,channels,None]))
        testpath=out/lang/'full-with-latent.onnx';onnx.save(extended,str(testpath))
        full=ort.InferenceSession(str(testpath),sess_options=opts,providers=['CPUExecutionProvider']);sample=dict(np.load(next((root/'aihub-aux-inputs'/('piper-'+lang)/'voice/calibration').glob('*.npz'))))
        actual=full.run(None,sample);value=actual[-1];length=value.shape[-1]
        # Validate dynamic vocoder exact full waveform, independently of static padding.
        dynamic=cut(model,[latent],outputs,{latent:[1,channels,None],outputs[0]:[1,1,1,None]});dynamicpath=out/lang/'vocoder-dynamic.onnx';onnx.save(dynamic,str(dynamicpath))
        split=ort.InferenceSession(str(dynamicpath),sess_options=opts,providers=['CPUExecutionProvider']);wave=split.run(None,{latent:value})[0];np.testing.assert_allclose(wave,actual[0],rtol=1e-5,atol=1e-5)
        static=ort.InferenceSession(str(file),sess_options=opts,providers=['CPUExecutionProvider']);chunk=value[:,:,:frames]
        if chunk.shape[-1]<frames:chunk=np.pad(chunk,[(0,0),(0,0),(0,frames-chunk.shape[-1])])
        result=static.run(None,{latent:chunk})[0];assert np.isfinite(result).all();np.savez_compressed(out/lang/'real-latent64.npz',**{latent:chunk})
        ledger[lang]=dict(folder=str(dest),input_name=latent,input_shape=[1,channels,frames],outputs=outputs,source=str(source),source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),full_waveform_split_parity=True,source_frames=length,
          scope='Vocoder64 only; dynamic frontend remains CPU; chunk overlap/quality/full mobile integration still required. Not a final TTS model.')
        (out/'exports.json').write_text(json.dumps(ledger,indent=2)+'\n');print('PIPER_SPLIT_PARITY',lang,length,result.shape,flush=True)
    from qai_hub.client import ClientConfig
    import qai_hub as hub
    client=hub.Client(ClientConfig(**json.loads(sys.stdin.readline())));jobsfile=out/'jobs.json';jobs=json.loads(jobsfile.read_text()) if jobsfile.exists() else {}
    for lang,row in ledger.items():
        if lang in jobs:continue
        job=client.submit_compile_job(row['folder'],device=hub.Device('Samsung Galaxy S24 Ultra'),options='--target_runtime precompiled_qnn_onnx --qairt_version 2.51 --qnn_options default_graph_htp_precision=FLOAT16',name='1Voice Piper '+lang+' vocoder64 static FP16 recovery',retry=False)
        jobs[lang]=dict(job_id=job.job_id,url=job.url,export=row);jobsfile.write_text(json.dumps(jobs,indent=2)+'\n');print('PIPER_RETRY_SUBMITTED',lang,job.job_id,flush=True)
if __name__=='__main__':main()
