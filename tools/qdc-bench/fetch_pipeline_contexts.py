"""Fetch the selected successful phone contexts, with credentials supplied on stdin."""
import argparse,json,sys,zipfile,shutil
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--out',required=True,type=Path);a=p.parse_args()
import qai_hub as h
from qai_hub.client import ClientConfig
c=h.Client(ClientConfig(**json.loads(sys.stdin.readline())))
rows={'asr/parakeet-v2/encoder':'jp1m7r78p','asr/parakeet-v2/decoder_joint':'jgdm8j8rg','piper/vi':'jpvne3zkg','piper/en':'jgj8oxkv5'}
for name,jid in rows.items():
 out=a.out/name;out.mkdir(parents=True,exist_ok=True)
 if (out/'model.onnx').is_file() and (out/'model.bin').is_file():continue
 job=c.get_job(jid);assert job.get_status().success,(name,str(job.get_status()))
 target=job.get_target_model();archive=Path(target.download(str(out/'download')))
 with zipfile.ZipFile(archive) as z:
  for entry in z.infolist():
   path=Path(entry.filename)
   if path.is_absolute() or '..' in path.parts:raise ValueError('Unsafe zip entry')
  for member in z.infolist():
   if Path(member.filename).name in ['model.onnx','model.bin']:
    with z.open(member) as inp,(out/Path(member.filename).name).open('wb') as dest:shutil.copyfileobj(inp,dest)
 assert (out/'model.onnx').is_file() and (out/'model.bin').is_file()
 (out/'provenance.json').write_text(json.dumps({'job_id':jid,'model_id':target.model_id,'qairt':'2.50','precision':'FP16 HTP; no integer quantization'},indent=2))
 archive.unlink();print('FETCHED',name,jid,flush=True)
