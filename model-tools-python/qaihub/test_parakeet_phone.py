import json,sys
from pathlib import Path
import numpy as np,onnxruntime as ort,qai_hub as h
from qai_hub.client import ClientConfig
r=Path('/home/opc/voxbridge-mt3-20261113');c=h.Client(ClientConfig(**json.loads(sys.stdin.readline())));fixtures=r/'parakeet-frontend-20261114';rows=json.loads((fixtures/'manifest.json').read_text());spec={'encoder':'jp1m7r78p','decoder_joint':'jgdm8j8rg'};jobs={}
opts=ort.SessionOptions();opts.intra_op_num_threads=3;encoder=ort.InferenceSession(str(r/'parakeet-v2-static-step-20261114/encoder/model.onnx'),sess_options=opts,providers=['CPUExecutionProvider'])
features=np.fromfile(fixtures/(rows[0]['id']+'.mel.f32'),dtype='<f4').reshape(1,128,1600);length=np.array([rows[0]['frames']],dtype=np.int64);encoded=encoder.run(None,{'audio_signal':features,'length':length})[0]
feeds={'encoder':{'audio_signal':[features],'length':[length.astype(np.int32)]},'decoder_joint':{'encoder_outputs':[encoded[:,:,:1].copy()],'targets':[np.array([[1024]],dtype=np.int32)],'target_length':[np.array([1],dtype=np.int32)],'input_states_1':[np.zeros((2,1,640),dtype=np.float32)],'input_states_2':[np.zeros((2,1,640),dtype=np.float32)]}}
for part,jid in spec.items():
 model=c.get_job(jid).get_target_model();inf=c.submit_inference_job(model,inputs=feeds[part],device=h.Device('Samsung Galaxy S24 Ultra'),options='--qairt_version 2.50',name='1Voice Parakeet v2 actual BTC feature component parity '+part);pro=c.submit_profile_job(model,device=h.Device('Samsung Galaxy S24 Ultra'),options='--qairt_version 2.50',name='1Voice Parakeet v2 phone component latency '+part)
 jobs[part]={'compile':jid,'inference':inf.job_id,'profile':pro.job_id};(r/'pipeline-phone-contexts/parakeet-cloud-tests.json').write_text(json.dumps(jobs,indent=2));print(part,jobs[part],flush=True)
 np.savez(r/'pipeline-phone-contexts'/('parakeet-'+part+'-feeds.npz'),**{k:v[0] for k,v in feeds[part].items()})
