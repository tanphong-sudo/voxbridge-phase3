#!/usr/bin/env python3
import argparse,json,sys,wave,hashlib
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--checkpoint',type=Path,required=True);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False)
 import torch,numpy as np
 torch.set_num_threads(4);torch.set_num_interop_threads(1)
 import nemo.collections.asr as na
 model=na.models.ASRModel.restore_from(str(a.checkpoint),map_location='cpu').float().eval();model.preprocessor.featurizer.dither=0.
 f=model.preprocessor.featurizer
 f.fb.detach().numpy().astype('<f4').tofile(a.out/'mel_128_257.f32');f.window.detach().numpy().astype('<f4').tofile(a.out/'hann_400.f32')
 pieces=model.tokenizer.ids_to_tokens(list(range(model.tokenizer.vocab_size)))
 (a.out/'tokens.json').write_text(json.dumps(pieces,ensure_ascii=False)+'\n')
 config={k:getattr(f,k,None) for k in ['preemph','normalize','n_fft','hop_length','win_length','mag_power','log_zero_guard_type','log_zero_guard_value','pad_to','pad_value']}
 (a.out/'frontend.json').write_text(json.dumps(config,indent=2,default=str)+'\n')
 rows=[json.loads(l) for l in a.manifest.read_text().splitlines() if l.strip()];manifest=[]
 for row in rows:
  if not row.get('speech_present',True):continue
  with wave.open(row['wav'],'rb') as w:
   assert w.getframerate()==16000 and w.getnchannels()==1 and w.getsampwidth()==2
   samples=np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').astype(np.float32)/32768.
  with torch.inference_mode():features,length=model.preprocessor(input_signal=torch.from_numpy(samples)[None],length=torch.tensor([len(samples)]))
  assert features.shape[-1]<=1600
  expected=np.zeros((1,128,1600),dtype=np.float32);expected[...,:features.shape[-1]]=features.numpy()
  name=row['id'];samples.astype('<f4').tofile(a.out/(name+'.pcm.f32'));expected.astype('<f4').tofile(a.out/(name+'.mel.f32'))
  manifest.append({'id':name,'frames':int(length.item()),'samples':len(samples),'wav_sha256':hashlib.sha256(Path(row['wav']).read_bytes()).hexdigest()})
 (a.out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');print('FRONTEND_FIXTURES',len(manifest),flush=True)
if __name__=='__main__':main()
