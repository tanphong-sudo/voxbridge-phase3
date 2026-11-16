#!/usr/bin/env python3
import json,html,argparse
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);args=p.parse_args();root=args.root;e=html.escape
 rows=[json.loads(l) for l in (root/'retry-details.jsonl').read_text().splitlines()]
 body=['<!doctype html><html lang="vi"><meta charset="utf-8"><title>Coverage/retry experiment</title><style>body{font:15px system-ui;margin:25px}table{width:100%;border-collapse:collapse}th,td{border:1px solid #ccc;padding:12px;vertical-align:top}th{background:#eee}input{padding:9px;width:50%}</style><h1>Kiểm mất ý/lặp và dịch lại từng câu</h1><p>40 đoạn × 2 model. Đây là thử đối chứng, không phải mọi câu đều được bộ kiểm tự chọn retry. Đáp án và nhận xét là AI nháp chưa có người kiểm chứng. chrF không phải % dịch đúng.</p><input id="q" placeholder="Tìm ID, câu, model"><table><tr><th>Model / nguồn</th><th>Dịch nguyên đoạn</th><th>Tách câu rồi dịch, ghép</th><th>Đáp án nháp</th></tr>']
 for r in rows:
  body.append('<tr class="data"><td>'+e(r['model']+' · '+r['id'])+'<p>'+e(r['source_text'])+'</p></td><td>'+e(r['first'])+'<p>chrF '+f"{r['first_chrf']:.2f}"+'</p></td><td>'+e(r['retry'])+'<p>chrF '+f"{r['retry_chrf']:.2f}"+'</p></td><td>'+e(r['reference_target'])+'</td></tr>')
 body.append('</table><script>document.getElementById("q").addEventListener("input",e=>{let q=e.target.value.toLowerCase();document.querySelectorAll("tr.data").forEach(r=>r.hidden=!r.textContent.toLowerCase().includes(q))})</script></html>')
 (root/'comparison.html').write_text(''.join(body));print('rows',len(rows))
if __name__=='__main__':main()
