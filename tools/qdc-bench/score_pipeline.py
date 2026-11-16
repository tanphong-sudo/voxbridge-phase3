#!/usr/bin/env python3
"""Report actual integrated first-write latency and output quality, excluding prompts from success."""
import argparse,json,math,re
from collections import Counter
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--results',type=Path,required=True);p.add_argument('--references',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
rows=list(map(json.loads,a.results.read_text().splitlines()));refs={r['id']:r for r in map(json.loads,a.references.read_text().splitlines())}
assert rows and len({r['id'] for r in rows})==len(rows) and all(r['id'] in refs for r in rows)
def percentile(values,q):
 if not values:return None
 values=sorted(values);position=(len(values)-1)*q;lo=math.floor(position);hi=math.ceil(position)
 return values[lo]*(hi-position)+values[hi]*(position-lo) if hi!=lo else values[lo]
def edit(x,y):
 prev=list(range(len(y)+1))
 for i,v in enumerate(x,1):
  now=[i]
  for j,w in enumerate(y,1):now.append(min(now[-1]+1,prev[j]+1,prev[j-1]+(v!=w)))
  prev=now
 return prev[-1]
from whisper_normalizer.english import EnglishTextNormalizer
from sacrebleu.metrics import CHRF
en=EnglishTextNormalizer()
report={'n':len(rows),'expected':len(refs),'complete':len(rows)==len(refs),'failures':[r['id'] for r in rows if r.get('failure')],'decisions':dict(Counter(r.get('decision','error') for r in rows)),'scope':'Integrated device WAV corpus, synthetic release to first AudioTrack write (acoustic proxy); preparation reported separately. Prompt playback and repair confirmation waits are not translated deadline success. References are frozen AI drafts, not human verified.','directions':{}}
for lang in ['vi','en']:
 group=[r for r in rows if r['source_lang']==lang];spoken=[r for r in group if r.get('translation_audio') and r.get('first_write_ms') is not None and not r.get('failure')]
 norm=en if lang=='en' else lambda s:' '.join(re.findall(r'[^\W_]+',s.casefold()))
 errors=words=0
 for r in group:
  reference=norm(refs[r['id']]['reference_source']).split();hyp=norm(r.get('asr_text','')).split();errors+=edit(reference,hyp);words+=len(reference)
 times=[r['first_write_ms'] for r in spoken];under=sum(t<2000 for t in times)
 report['directions'][lang]={'n':len(group),'translated_audio':len(spoken),'under_2s':under,'under_2s_fraction_all_inputs':under/len(group) if group else None,'first_write_ms':{'p50':percentile(times,.5),'p95':percentile(times,.95),'max':max(times) if times else None},'wer_all_inputs_including_skips':errors/words if words else None,'chrf_spoken_subset':CHRF().corpus_score([r.get('output_text','') for r in spoken],[[refs[r['id']]['reference_target'] for r in spoken]]).score if spoken else None}
a.out.write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps(report,ensure_ascii=False,indent=2))
