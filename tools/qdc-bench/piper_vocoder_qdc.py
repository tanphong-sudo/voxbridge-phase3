import sys,json,time,subprocess
from pathlib import Path
import numpy as np,onnxruntime as ort
from remote import Remote
root=Path('/home/opc/voxbridge-mt3-20261113/qdc-bench');manifest=json.loads((root/'piper-qnn-models/models.json').read_text())
cmd=['/home/opc/android-sdk/platform-tools/adb','-s','80003b9c'];dest='/sdcard/Android/data/com.voxbridge.qdcbench/files/models'
for name,row in manifest.items():
 for file in row['files']:
  source=root/'piper-qnn-models'/file['path'];target=dest+'/'+file['path'];subprocess.run(cmd+['shell','mkdir -p '+str(Path(target).parent)],check=True,capture_output=True);subprocess.run(cmd+['push',str(source),target],check=True,capture_output=True)
subprocess.run(cmd+['shell','chown -R 10224:10224 /data/media/0/Android/data/com.voxbridge.qdcbench/files/models/piper-*; restorecon -RF /data/media/0/Android/data/com.voxbridge.qdcbench/files/models/piper-*'],check=True,capture_output=True)
bridge=Remote('127.0.0.1',28900,(root/'.session-token').read_text().strip());out=root/'piper-vocoder-qdc';out.mkdir(exist_ok=True);summary={}
for lang in ['vi','en']:
 item=manifest['piper-'+lang+'/vocoder64'];loaded=bridge.request('load',models={'vocoder':'models/'+item['onnx']});feed=dict(np.load(root.parent/'piper-qnn-retry-rank4'/lang/'real-latent64.npz'));times=[]
 opts=ort.SessionOptions();opts.intra_op_num_threads=3;s=ort.InferenceSession(str(root.parent/'piper-qnn-retry-rank4'/lang/'vocoder64/model.onnx'),sess_options=opts,providers=['CPUExecutionProvider']);expected=s.run(None,feed)[0]
 for i in range(11):
  bridge.reset();handles=bridge.run('vocoder',feed);actual=bridge.get(next(iter(handles.values()))).reshape(expected.shape)
  if i:times.append(bridge.run_ms)
 np.save(out/(lang+'-qnn.npy'),actual);noise=np.mean((actual.astype(np.float64)-expected)**2);power=np.mean(expected.astype(np.float64)**2)
 summary[lang]=dict(graph_p50_ms=float(np.median(times)),graph_p95_ms=float(np.percentile(times,95)),snr_db=float(10*np.log10(power/max(noise,1e-30))),max_abs=float(np.max(np.abs(actual-expected))),finite=bool(np.isfinite(actual).all()),samples=actual.size,scope='vocoder64 only; frontend/phonemes/chunk overlap not included; not full TTS latency')
 profiles=bridge.request('unload');(out/(lang+'-profiles.json')).write_text(json.dumps(profiles,indent=2));print('QDC_PIPER_VOCODER',lang,summary[lang],flush=True)
(out/'summary.json').write_text(json.dumps(summary,indent=2));bridge.close()
