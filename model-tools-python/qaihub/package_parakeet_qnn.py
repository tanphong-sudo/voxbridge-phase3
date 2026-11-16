"""Package exact external tensors using QAI-accepted filenames, preserving the source graph."""
import argparse,json,os,shutil
from pathlib import Path
import onnx
from onnx.external_data_helper import _get_all_tensors

def pack(src,out):
 out.mkdir(parents=True,exist_ok=False);g=onnx.load(str(src/'model.onnx'),load_external_data=False);copied=set()
 for tensor in _get_all_tensors(g):
  for item in tensor.external_data:
   if item.key=='location':
    old=item.value;new=old if old.endswith('.bin') else old+'.bin'
    if old not in copied:
     origin=src/old
     if not origin.is_file():raise FileNotFoundError(str(origin))
     # Same-volume immutable source files can be linked without duplicating gigabytes.
     try:os.link(origin,out/new)
     except OSError:shutil.copyfile(origin,out/new)
     copied.add(old)
    item.value=new
 onnx.save(g,str(out/'model.onnx'))
 return {'source':str(src),'external_files':len(copied)}

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False)
 report={n:pack(a.root/n,a.out/n) for n in ['encoder','decoder_joint']}
 (a.out/'packaging.json').write_text(json.dumps(report,indent=2)+'\n');print('PACKED',a.out)
if __name__=='__main__':main()
