#!/usr/bin/env python3
import argparse,json,re,unicodedata,statistics
from pathlib import Path

def words(text):return re.findall(r'[^\W_]+',unicodedata.normalize('NFKC',text.casefold()),re.UNICODE)
def edits(a,b):
 row=list(range(len(b)+1))
 for i,x in enumerate(a,1):
  new=[i]
  for j,y in enumerate(b,1):new.append(min(row[j]+1,new[-1]+1,row[j-1]+(x!=y)))
  row=new
 return row[-1]
def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);a=p.parse_args();summary={};records={}
 for mode in ['fp32','int8']:
  rows=[json.loads(x) for x in (a.root/(mode+'.jsonl')).read_text().splitlines()];assert len(rows)==100 and len({r['id'] for r in rows})==100
  errors=sum(edits(words(r['reference']),words(r['hypothesis'])) for r in rows);n=sum(len(words(r['reference'])) for r in rows)
  summary[mode]=dict(n=len(rows),errors=errors,reference_words=n,wer=errors/n,failures=sum(r.get('failure') is not None for r in rows),
   p50_ms=statistics.median(r['elapsed_ms'] for r in rows if not r.get('failure')));records[mode]={r['id']:r for r in rows}
 summary['same_text']=sum(records['fp32'][id]['hypothesis']==records['int8'][id]['hypothesis'] for id in records['fp32'])
 summary['scope']='same100 VI WAVs through actual Kotlin VAD/mel/Whisper decode; existing teammate CPU INT8, not AI Hub or Snapdragon; case-sensitive text equality, WER normalized'
 (a.root/'summary.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary,indent=2))
if __name__=='__main__':main()
