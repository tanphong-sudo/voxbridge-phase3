import argparse,json,subprocess
from pathlib import Path
from tokenizers import Tokenizer
p=argparse.ArgumentParser();p.add_argument('--fixtures',type=Path,required=True);p.add_argument('--classpath',required=True);p.add_argument('--java',default='java');p.add_argument('--out',type=Path,required=True);a=p.parse_args()
rows=list(map(json.loads,(a.fixtures/'mt200.jsonl').read_text().splitlines()));report={}
edges=['mo\u031b\u0309','mo\u031b\u0309 máy 5','Tôi không tắt máy','Dừng máy số ５！','Không ngắt điện 220 V.','Xin\u00a0chào.','Stop machine 5, not 3.','Chào\tanh\nnhé.','安全！ Việt Nam.','a\u0301\u0323','𠀀 abc','Thợ đang ở đâu?']
for name in ['envit5','vinai-vi2en','vinai-en2vi']:
 tok=Tokenizer.from_file(str(a.fixtures/'tokenizers'/name/'tokenizer.json'));tok.enable_padding(length=64,pad_id=tok.token_to_id('<pad>'),pad_token='<pad>')
 selected=[{'text':r['source_text'],'source':r['source_lang']} for r in rows if name=='envit5' or r['source_lang']==('vi' if name=='vinai-vi2en' else 'en')]
 selected += [{'text':text,'source':'vi' if name!='vinai-en2vi' else 'en'} for text in edges]
 command=[a.java,'-cp',a.classpath,'TokenizerParityCli',str(a.fixtures/'tokenizers'/name/'tokenizer.json')]
 outputs=subprocess.run(command,input=''.join(json.dumps(r)+'\n' for r in selected),text=True,capture_output=True,check=True)
 actual=list(map(json.loads,outputs.stdout.splitlines()));assert len(actual)==len(selected)
 failures=[]
 for row,got in zip(selected,actual):
  expected=tok.encode((row['source']+': ' if name=='envit5' else '')+row['text']);ids=expected.ids
  if got.get('ids')!=ids or got.get('mask')!=expected.attention_mask:failures.append({'input':row,'got':got,'expected_ids':ids})
 report[name]={'n':len(selected),'matched':len(selected)-len(failures),'failures':failures}
a.out.write_text(json.dumps(report,ensure_ascii=False,indent=2));print({k:{x:v[x] for x in ['n','matched']} for k,v in report.items()})
if any(v['failures'] for v in report.values()):raise SystemExit(1)
