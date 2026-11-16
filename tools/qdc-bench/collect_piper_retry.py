import json,sys
from pathlib import Path
import numpy as np,onnxruntime as ort,qai_hub as hub
from qai_hub.client import ClientConfig
root=Path('/home/opc/voxbridge-mt3-20261113/piper-qnn-retry-rank4');client=hub.Client(ClientConfig(**json.loads(sys.stdin.readline())));tests=json.loads((root/'tests.json').read_text());summary={}
for lang,row in tests.items():
 infer=client.get_job(row['inference']);profile=client.get_job(row['profile']);record=dict(inference_status=str(infer.get_status()),profile_status=str(profile.get_status()),scope='Piper static vocoder64 only; not complete TTS, not Android hybrid integration')
 if infer.get_status().success:
  outputs=infer.download_output_data();actual=np.asarray(next(iter(outputs.values()))[0]);feed=dict(np.load(root/lang/'real-latent64.npz'))
  opts=ort.SessionOptions();opts.intra_op_num_threads=3;s=ort.InferenceSession(str(root/lang/'vocoder64/model.onnx'),sess_options=opts,providers=['CPUExecutionProvider']);expected=s.run(None,feed)[0];actual=actual.reshape(expected.shape)
  np.save(root/lang/'qnn-output.npy',actual);noise=np.mean((actual.astype(np.float64)-expected)**2);power=np.mean(expected.astype(np.float64)**2)
  record.update(finite=bool(np.isfinite(actual).all()),max_abs=float(np.max(np.abs(actual-expected))),rmse=float(np.sqrt(noise)),snr_db=float(10*np.log10(power/max(noise,1e-30))),samples=actual.size)
 if profile.get_status().success:
  data=profile.download_profile();(root/lang/'profile.json').write_text(json.dumps(data,indent=2));values=data['execution_summary']['all_inference_times'];record.update(best_ms=data['execution_summary']['estimated_inference_time']/1000,p50_ms=float(np.median(values))/1000,p95_ms=float(np.percentile(values,95))/1000)
 summary[lang]=record
(root/'summary.json').write_text(json.dumps(summary,indent=2));print(json.dumps(summary,indent=2))
