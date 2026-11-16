import sys,json
from pathlib import Path
import numpy as np
import qai_hub as hub
from qai_hub.client import ClientConfig
root=Path('/home/opc/voxbridge-mt3-20261113/piper-qnn-retry-rank4');client=hub.Client(ClientConfig(**json.loads(sys.stdin.readline())));jobs=json.loads((root/'jobs.json').read_text());ledger={}
for lang,row in jobs.items():
 model=client.get_job(row['job_id']).get_target_model();data={k:[v] for k,v in dict(np.load(root/lang/'real-latent64.npz')).items()}
 infer=client.submit_inference_job(model,inputs=data,device=hub.Device('Samsung Galaxy S24 Ultra'),name='1Voice Piper '+lang+' vocoder64 exact waveform parity',options='--qairt_version 2.51')
 profile=client.submit_profile_job(model,device=hub.Device('Samsung Galaxy S24 Ultra'),name='1Voice Piper '+lang+' vocoder64 FP16 profile',options='--qairt_version 2.51')
 ledger[lang]=dict(compile=row['job_id'],inference=infer.job_id,profile=profile.job_id);(root/'tests.json').write_text(json.dumps(ledger,indent=2));print('SUBMITTED_PIPER_TEST',lang,ledger[lang],flush=True)
