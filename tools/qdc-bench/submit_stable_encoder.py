import json,sys
from pathlib import Path
import qai_hub as hub
from qai_hub.client import ClientConfig
client=hub.Client(ClientConfig(**json.loads(sys.stdin.readline())))
root=Path('/home/opc/voxbridge-mt3-20261113');out=root/'qdc-bench/envit5-stable-compile.json'
if out.exists():print('EXISTING',out.read_text());raise SystemExit(0)
parity=json.loads((root/'envit5-stable-encoder/parity.json').read_text());assert parity['n']==200
outputs=['encoder_hidden_states']+[f'cross_{i}_{k}' for i in range(12) for k in ['k','v']]
import shutil
folder=root/'envit5-stable-upload';folder.mkdir(exist_ok=True)
for filename in ['model.onnx','model.data']:shutil.copy2(root/'envit5-stable-encoder'/filename,folder/filename)
job=client.submit_compile_job(str(folder),device=hub.Device('Samsung Galaxy S24 Ultra'),options='--target_runtime precompiled_qnn_onnx --qairt_version 2.50 --qnn_options default_graph_htp_precision=FLOAT16 --output_names '+','.join(outputs),name='1Voice EnViT5 encoder stable RMSNorm FP16 QDC recovery',retry=False)
out.write_text(json.dumps(dict(job_id=job.job_id,url=job.url,parity=parity,outputs=outputs,source_change='RMSNorm square scaling32; original weights unchanged'),indent=2)+'\n')
print('SUBMITTED_STABLE_ENCODER',job.job_id,flush=True)
