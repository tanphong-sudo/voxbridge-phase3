#!/usr/bin/env python3
"""Generate a local side-by-side report; escapes corpus text and all model output."""
import argparse
import html
import json
from pathlib import Path

NAMES={'raw':'Giữ nguyên','casing':'Chỉ viết hoa','old_rule':'Luật cũ (chỉ VI)','neural_full':'Model dấu + chữ hoa','neural_terminal':'Model chỉ dấu hỏi + lọc','clean_source':'Nguồn chuẩn (mốc)'}
def main():
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);args=p.parse_args();root=args.root
    esc=html.escape;content=[]
    content.append('''<!doctype html><html lang="vi"><meta charset="utf-8"><title>BTC 200 — So sánh dấu câu và dịch</title><style>body{font:15px system-ui;margin:24px;color:#202020}table{border-collapse:collapse;width:100%}td,th{border:1px solid #ddd;padding:10px;vertical-align:top}th{background:#f2f4f5;position:sticky;top:0}small{color:#555}.translation{margin-top:12px}input,select{padding:8px;margin:10px 10px 18px 0}summary{cursor:pointer}td{min-width:170px} .wrap{overflow:auto}</style><h1>BTC 200 — Dấu câu trước bước dịch</h1><p>Tất cả 200 câu, không chia dev/test. Đầu vào thử: chữ thường và thay ? bằng dấu chấm; không phải 200 transcript ASR thật. Đáp án AI nháp chưa được người kiểm chứng. chrF đo mức giống đáp án, không phải % dịch đúng. Cột luật cũ chỉ tác động tiếng Việt.</p>''')
    content.append('<table><tr><th>Cách</th><th>EnViT5 chrF / 200</th><th>VinAI chrF / 200</th></tr>')
    summaries={m:json.loads((root/m/'summary.json').read_text()) for m in ['envit5','vinai']}
    for v,name in NAMES.items():
        content.append('<tr><td>'+esc(name)+'</td>'+''.join('<td>'+f"{summaries[m]['groups']['all200']['variants'][v]['chrf']:.2f}"+'</td>' for m in summaries)+'</tr>')
    content.append('</table><p><input id="search" placeholder="Tìm ID, câu hoặc bản dịch"><select id="model"><option value="">Cả hai model</option><option>envit5</option><option>vinai</option></select><select id="lang"><option value="">Cả hai chiều</option><option value="vi">Việt → Anh</option><option value="en">Anh → Việt</option></select><span id="count"></span></p><div class="wrap"><table id="details"><thead><tr><th>Nguồn / đáp án</th>'+''.join('<th>'+esc(n)+'</th>' for n in NAMES.values())+'</tr></thead><tbody>')
    total=0
    for model in summaries:
        rows=[json.loads(l) for l in (root/model/'paired-details.jsonl').read_text().splitlines()]
        for r in rows:
            total+=1
            content.append(f'<tr data-model="{model}" data-lang="{r["source_lang"]}"><td><b>{esc(r["id"])}</b> · {model}<p>{esc(r["source_text"])}</p><details><summary>Đáp án nháp</summary><p>{esc(r["reference_target"])}</p></details></td>')
            for v in NAMES:
                content.append('<td><small>'+esc(r['variants'][v])+'</small><div class="translation">'+esc(r['outputs'][v])+'</div><p><small>chrF câu '+f"{r['sentence_chrf'][v]:.1f}"+'</small></p></td>')
            content.append('</tr>')
    content.append('''</tbody></table></div><script>const inputs=['search','model','lang'].map(id=>document.getElementById(id));function filter(){let n=0;const [s,m,l]=inputs.map(x=>x.value.toLowerCase());document.querySelectorAll('#details tbody tr').forEach(r=>{const show=(!s||r.textContent.toLowerCase().includes(s))&&(!m||r.dataset.model===m)&&(!l||r.dataset.lang===l);r.hidden=!show;if(show)n++});document.getElementById('count').textContent=n+' dòng'}inputs.forEach(i=>i.addEventListener('input',filter));filter();</script></html>''')
    (root/'comparison.html').write_text(''.join(content));print('rows',total,'report',root/'comparison.html')
if __name__=='__main__':main()
