import json,sys
from pathlib import Path
import numpy as np,qai_hub as h
from qai_hub.client import ClientConfig
root=Path('/home/opc/voxbridge-mt3-20261113');c=h.Client(ClientConfig(**json.loads(sys.stdin.readline())));jobs=json.loads((root/'piper-masked64/jobs.json').read_text());ledger={}
for lang,row in jobs.items():
 model=c.get_job(row['job_id']).get_target_model();latent=np.load(root/'piper-hybrid-front'/lang/'bridge-proof.npz')['latent'];data={'/Mul_7_output_0':[],'valid_frames':[]}
 for size in [1,2,8,16,32,48,63,64]:
  data['/Mul_7_output_0'].append(np.pad(latent[:,:,:size],[(0,0),(0,0),(0,64-size)]));data['valid_frames'].append(np.array([size],dtype=np.int32))
 infer=c.submit_inference_job(model,inputs=data,device=h.Device('Samsung Galaxy S24 Ultra'),name='1Voice Piper masked64 short+full boundary quality '+lang,options='--qairt_version 2.50')
 profile=c.submit_profile_job(model,device=h.Device('Samsung Galaxy S24 Ultra'),name='1Voice Piper masked64 QNN phone latency '+lang,options='--qairt_version 2.50')
 ledger[lang]={'inference':infer.job_id,'profile':profile.job_id,'compile':row['job_id']};(root/'piper-masked64/tests.json').write_text(json.dumps(ledger,indent=2));print(lang,ledger[lang],flush=True)
