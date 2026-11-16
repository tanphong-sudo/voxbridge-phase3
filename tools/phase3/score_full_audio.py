#!/usr/bin/env python3
"""Validate real full-audio harness output and report functional/text metrics honestly."""
import argparse,json,re,hashlib,wave,statistics
from pathlib import Path
from collections import Counter

def edit_distance(a,b):
 prev=list(range(len(b)+1))
 for i,x in enumerate(a,1):
  row=[i]
  for j,y in enumerate(b,1):row.append(min(row[-1]+1,prev[j]+1,prev[j-1]+(x!=y)))
  prev=row
 return prev[-1]
def normalize(text):return ' '.join(re.findall(r'[^\W_]+',text.casefold()))
def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);args=p.parse_args();root=args.root
 from sacrebleu.metrics import CHRF
 from whisper_normalizer.english import EnglishTextNormalizer
 english=EnglishTextNormalizer();metric=CHRF(word_order=0)
 manifest=[json.loads(l) for l in (root/'manifest200.jsonl').read_text().splitlines()];expected={r['id']:r for r in manifest};assert len(expected)==200
 report=dict(scope='VPS functional full WAV pipeline. Production Kotlin VAD/ASR/core; local HF FP32 MT and same Piper voice/settings via Python sherpa-onnx. No microphone, hardware playback or on-device latency claim.',models={})
 for model in ['envit5','vinai']:
  rows=[json.loads(l) for l in (root/model/'results.jsonl').read_text().splitlines()];assert len(rows)==len({r['id'] for r in rows})==200 and {r['id'] for r in rows}==set(expected)
  audio_verified=0;policy_errors=[]
  for r in rows:
   assert r['input_sha256']==expected[r['id']]['sha256']
   if r.get('tts'):
    p=Path(r['tts']['wav'])
    assert p.is_file() and hashlib.sha256(p.read_bytes()).hexdigest()==r['tts']['sha256']
    with wave.open(str(p)) as w:
     assert w.getnchannels()==1 and w.getsampwidth()==2 and w.getframerate()==r['tts']['sample_rate'] and w.getnframes()==r['tts']['samples'] and w.getnframes()>0
    audio_verified+=1
   if r.get('decision') in ['AskRepeat','NoSpeech','RepairedTranslation'] and r.get('translation_audio_generated'):policy_errors.append(r['id'])
   if r.get('mt_calls',0)>1:policy_errors.append(r['id']+':extra_mt_call')
  accepted=[r for r in rows if r.get('translation_audio_generated') and r.get('final_text')]
  raw=[r for r in rows if r.get('raw_mt')]
  scores={}
  for lang in ['vi','en']:
   group=[r for r in rows if r['source_lang']==lang];asr=[r for r in group if r.get('raw_asr')]
   norm=english if lang=='en' else normalize
   wer_num=wer_den=cer_num=cer_den=0
   for r in asr:
    ref=norm(r['reference_source']);hyp=norm(r['raw_asr']);wer_num+=edit_distance(ref.split(),hyp.split());wer_den+=len(ref.split());cer_num+=edit_distance(ref,hyp);cer_den+=len(ref)
   all_wer_num=all_wer_den=0
   for r in group:
    ref=norm(r['reference_source']);hyp=norm(r.get('raw_asr',''));all_wer_num+=edit_distance(ref.split(),hyp.split());all_wer_den+=len(ref.split())
   scores[lang]=dict(n=len(group),asr_outputs=len(asr),wer_all_inputs_including_no_speech=all_wer_num/all_wer_den if all_wer_den else None,asr_wer=wer_num/wer_den if wer_den else None,asr_cer=cer_num/cer_den if cer_den else None,normalization='whisper_normalizer EnglishTextNormalizer' if lang=='en' else 'lowercase, Unicode letters/numbers, whitespace; no Vietnamese numeric-word equivalence')
  times=sorted(r['pipeline_to_audio_ready_ms'] for r in rows if not r.get('failure'))
  report['models'][model]=dict(n=200,failures=[dict(id=r['id'],failure=r['failure']) for r in rows if r.get('failure')],decisions=dict(Counter(r.get('decision','error') for r in rows)),translation_wavs=sum(r.get('translation_audio_generated',False) for r in rows),prompt_wavs=sum(bool(r.get('tts',{}).get('cached_prompt')) for r in rows),awaiting_confirmation=sum(r.get('awaiting_confirmation',False) for r in rows),audio_files_verified=audio_verified,policy_errors=policy_errors,asr=scores,raw_mt_chrf=metric.corpus_score([r.get('raw_mt','') for r in rows],[[r['reference_target'] for r in rows]]).score,raw_mt_n=len(raw),spoken_subset_chrf=metric.corpus_score([r['final_text'] for r in accepted],[[r['reference_target'] for r in accepted]]).score if accepted else None,spoken_subset_n=len(accepted),measured_case_processing_total_s=sum(r['pipeline_to_audio_ready_ms'] for r in rows)/1000,vps_audio_ready_ms=dict(p50=statistics.median(times),p95=times[round((len(times)-1)*.95)],includes_loads=True,scope='file ready to complete WAV ready on loaded VPS; excludes input realtime replay and speaker playback; not Snapdragon first-audio latency'),reference_status='Existing frozen AI drafts; human_verified=0; no full listening review')
 (root/'summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
