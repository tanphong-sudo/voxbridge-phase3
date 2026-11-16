"""Stage the integrated phone pipeline without CPU ASR/MT weights or benchmark answers as inputs."""
import argparse,hashlib,json,os,shutil,wave
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--root',required=True,type=Path);p.add_argument('--out',required=True,type=Path);p.add_argument('--apk',required=True,type=Path);a=p.parse_args()
r=a.root;out=a.out;models=out/'files/models';models.mkdir(parents=True,exist_ok=True)
def sha(f):
 h=hashlib.sha256()
 with f.open('rb') as stream:
  while block:=stream.read(4*1024*1024):h.update(block)
 return h.hexdigest()
def copy(src,dest):
 src=src.resolve(strict=True);dest.parent.mkdir(parents=True,exist_ok=True)
 if dest.exists():
  if sha(src)==sha(dest):return
  dest.unlink()
 try:os.link(src,dest)
 except OSError:shutil.copyfile(src,dest)
def tree(src,dest):
 for f in src.rglob('*'):
  if f.is_file() and not f.name.startswith('.') and f.suffix!='.zip':copy(f,dest/f.relative_to(src))
base=r/'pipeline-current-models';copy(base/'vad/silero_vad.onnx',models/'vad/silero_vad.onnx')
# Only the sidecars actually used by the frozen VI frontend/token decoder.
for name in ['mel_80_201.f32.bin','whisper_tokens.json','vocab.json','tokenizer.json','merges.txt','tokenizer_config.json','special_tokens_map.json']:
 src=base/'speech_to_text/phowhisper-small-onnx'/name
 if src.is_file():copy(src,models/'speech_to_text/phowhisper-small-onnx'/name)
contexts=r/'pipeline-phone-contexts'
for part in ['encoder','decoder_joint']:tree(contexts/'asr/parakeet-v2'/part,models/'speech_to_text/parakeet-v2-qnn'/part)
for name in ['tokens.json','mel_128_257.f32','hann_400.f32','frontend.json']:copy(r/'parakeet-frontend-20261114'/name,models/'speech_to_text/parakeet-v2-onnx'/name)
standalone=r/'qdc-bench-ready-20261114';manifest=json.loads((standalone/'models/models.json').read_text());mt={}
for key,entry in manifest.items():
 if key.startswith('phowhisper-small/'):
  part=key.split('/')[1];folder=models/'speech_to_text/phowhisper-small-qnn'/('encoder' if part=='encoder' else 'decoder_joint')
  wrapper=standalone/'models'/entry['onnx'];copy(wrapper,folder/'model.onnx')
  for row in entry['files']:
   f=standalone/'models'/row['path']
   if f.suffix=='.bin':copy(f,folder/f.name)
 elif key.split('/')[0] in ['envit5','vinai-vi2en','vinai-en2vi']:
  for row in entry['files']:
   src=standalone/'models'/row['path'];assert src.stat().st_size==row['bytes'] and sha(src)==row['sha256'];copy(src,models/'translation/qnn/models'/row['path'])
  mt[key]=entry
(models/'translation/qnn/models/models.json').write_text(json.dumps(mt,indent=2))
for name in ['envit5','vinai-vi2en','vinai-en2vi']:tree(standalone/'tokenizers'/name,models/'translation/qnn/tokenizers'/name)
for lang,voice in [('vi','vits-piper-vi_VN-vais1000-medium'),('en','vits-piper-en_US-lessac-medium')]:
 voiceDir=base/'text_to_speech/piper'/voice
 for f in voiceDir.rglob('*'):
  if f.is_file() and f.suffix!='.onnx':copy(f,models/'text_to_speech/piper'/voice/f.relative_to(voiceDir))
 copy(r/'piper-hybrid-front'/lang/'frontend.onnx',models/'text_to_speech/piper'/voice/'frontend.onnx')
 tree(contexts/'piper'/lang,models/'text_to_speech/piper-qnn'/lang)
tree(base/'text_to_speech/cache',models/'text_to_speech/cache')
rows=[json.loads(l) for l in (r/'full-audio-btc200/manifest200.jsonl').read_text().splitlines()];assert len(rows)==len({x['id'] for x in rows})==200
corpus=out/'files/corpus';corpus.mkdir(parents=True,exist_ok=True);inference=[];refs=[]
for row in rows:
 src=Path(row['wav']);assert sha(src)==row['sha256']
 with wave.open(str(src)) as w:assert (w.getnchannels(),w.getsampwidth(),w.getframerate())==(1,2,16000)
 relative='corpus/wav/'+row['id']+'.wav';copy(src,out/'files'/relative)
 item={k:row[k] for k in ['id','source_lang','target_lang','sha256']};item['wav']=relative;inference.append(item)
 refs.append({k:row[k] for k in ['id','reference_source','reference_target']})
(corpus/'manifest200.jsonl').write_text(''.join(json.dumps(x,ensure_ascii=False)+'\n' for x in inference))
smoke=[next(x for x in inference if x['source_lang']==lang) for lang in ['vi','en']]
(corpus/'smoke2.jsonl').write_text(''.join(json.dumps(x,ensure_ascii=False)+'\n' for x in smoke))
# Golden answers stay on the host, separate from the model inputs staged to the phone.
(out/'references200.jsonl').write_text(''.join(json.dumps(x,ensure_ascii=False)+'\n' for x in refs))
entries=[]
for f in sorted(models.rglob('*')):
 if f.is_file() and f.name!='integrity.sha256' and not f.name.startswith('.'):
  entries.append(f'{sha(f)} {f.stat().st_size} {f.relative_to(models).as_posix()}\n')
(models/'integrity.sha256').write_text(''.join(entries))
copy(a.apk,out/'voxbridge-qdc-pipeline.apk')
(out/'apk.sha256').write_text(sha(out/'voxbridge-qdc-pipeline.apk')+'  voxbridge-qdc-pipeline.apk\n')
report={'staged':True,'device_smoke_tested':False,'target':'SM8650','qairt':'2.50','asr':{'vi':'PhoWhisper-small','en':'Parakeet-TDT-0.6B-v2'},'mt':['EnViT5','VinAI vi2en/en2vi'],'tts':'Piper CPU phonemizer/duration/flow + masked64 QNN vocoder','files':len(entries),'model_bytes':sum(f.stat().st_size for f in models.rglob('*') if f.is_file()),'cases':200,'deadline_claim':None}
(out/'STAGING.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
