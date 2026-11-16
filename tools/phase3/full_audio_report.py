#!/usr/bin/env python3
import argparse,json,html
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);args=p.parse_args();root=args.root;e=html.escape
 body=['<!doctype html><html lang="vi"><meta charset="utf-8"><title>Full audio BTC200</title><style>body{font:15px system-ui;margin:24px}table{width:100%;border-collapse:collapse}th,td{border:1px solid #ddd;padding:10px;vertical-align:top}th{background:#eee}input{padding:10px;width:50%}small{color:#555}</style><h1>200 WAV × 2 model — full pipeline VPS</h1><p>VAD/ASR/core Kotlin thật; MT FP32/Piper qua worker local. Có file WAV đầu ra; không đo mic/loa hay Snapdragon. Đáp án AI nháp chưa được người kiểm chứng.</p><input id="q" placeholder="Tìm câu, ID, model hoặc quyết định"><table><tr><th>Case</th><th>Nguồn</th><th>ASR thật</th><th>Dịch / quyết định</th><th>TTS</th></tr>']
 for model in ['envit5','vinai']:
  for r in map(json.loads,(root/model/'results.jsonl').read_text().splitlines()):
   tts=r.get('tts',{});audio=''
   local=root/model/'audio'/(r['id']+'.wav')
   if local.exists():audio='<audio controls preload="none" src="'+e(str(local.resolve()))+'"></audio>'
   body.append('<tr class="data"><td>'+e(model+' '+r['id'])+'</td><td>'+e(r.get('reference_source',''))+'</td><td>'+e(r.get('raw_asr',''))+'</td><td>'+e(r.get('raw_mt',''))+'<p><b>'+e(r.get('decision','error'))+'</b></p><small>'+e(r.get('failure') or r.get('decision_detail',''))+'</small></td><td>'+audio+'<p>'+e(str(tts.get('wav','No audio / awaiting confirmation')))+'</p><small>'+e(str(tts.get('duration_s','')))+' s; prompt='+e(str(tts.get('cached_prompt','')) )+'</small></td></tr>')
 body.append('</table><script>document.getElementById("q").addEventListener("input",e=>{const q=e.target.value.toLowerCase();document.querySelectorAll("tr.data").forEach(r=>r.hidden=!r.textContent.toLowerCase().includes(q))})</script></html>')
 (root/'comparison.html').write_text(''.join(body));print(root/'comparison.html')
if __name__=='__main__':main()
