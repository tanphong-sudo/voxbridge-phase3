#!/usr/bin/env python3
"""Frozen coverage controls, real-output diagnostics and bounded sentence-retry experiment."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

# Source, target sentences and a compact meaning-preserving paraphrase for each direction.
PAIRS=[
(['Dừng máy.','Chờ tôi đến.'],['Stop the machine.','Wait for me.'],'Dừng máy và đợi tôi.','Stop the machine and wait for me.'),
(['Đừng mở van số 3.','Gọi quản đốc.'],['Do not open valve number 3.','Call the supervisor.'],'Đừng mở van 3, hãy gọi quản đốc.','Keep valve 3 closed and call the supervisor.'),
(['Ngắt nguồn điện.','Kiểm tra dây tiếp đất.'],['Disconnect the power.','Check the grounding wire.'],'Ngắt điện rồi kiểm tra dây đất.','Cut the power and inspect the earth wire.'),
(['Đeo kính bảo hộ.','Đóng cửa tủ điện.'],['Wear safety goggles.','Close the electrical cabinet door.'],'Đeo kính và đóng cửa tủ điện.','Put on goggles and shut the electrical cabinet.'),
(['Gọi đội cứu hộ.','Chờ ở cổng chính.'],['Call the rescue team.','Wait at the main gate.'],'Gọi cứu hộ rồi đợi ở cổng chính.','Phone rescue and stay at the main gate.'),
(['Kiểm tra van B-07.','Gửi ảnh cho tôi.'],['Check valve B-07.','Send me a photo.'],'Kiểm tra van B-07 và gửi tôi ảnh.','Inspect valve B-07 and send me a picture.'),
(['Mở cửa bên trái.','Đợi mọi người ra ngoài.'],['Open the door on the left.','Wait for everyone to leave.'],'Mở cửa trái và chờ mọi người ra.','Open the left door and let everyone leave.'),
(['Xác nhận máy đã dừng.','Gọi cho tôi trước khi sửa.'],['Confirm the machine has stopped.','Call me before repairs.'],'Xác nhận máy dừng rồi gọi tôi trước khi sửa.','Make sure the machine is stopped and phone me before repairs.'),
(['Nói lại số phòng.','Gửi địa chỉ cho tôi.'],['Repeat the room number.','Send me the address.'],'Nhắc lại số phòng và gửi tôi địa chỉ.','Say the room number again and forward the address to me.'),
(['Sơ tán khỏi khu vực này.','Đợi ở bên ngoài.'],['Evacuate this area.','Wait outside.'],'Ra khỏi khu vực này và chờ bên ngoài.','Leave this area and stay outside.'),
(['Tôi cần hóa đơn.','Hàng phải đến trước trưa mai.'],['I need an invoice.','The goods must arrive before noon tomorrow.'],'Tôi cần hóa đơn và hàng trước trưa mai.','I need the invoice and goods by noon tomorrow.'),
(['Tôi chưa nghe rõ.','Xin nói lại chậm hơn.'],["I didn't hear clearly.",'Please repeat that more slowly.'],'Tôi nghe chưa rõ, xin nhắc lại chậm hơn.','Please say it again slowly; I missed it.'),
(['Áp suất là 3.5 bar.','Đừng mở van A-12.'],['The pressure is 3.5 bar.','Do not open valve A-12.'],'Áp suất 3.5 bar, không mở van A-12.','At 3.5 bar, keep valve A-12 closed.'),
(['Bà đang ở tầng hai.','Cầu thang nằm bên trái.'],['She is on the second floor.','The stairs are on the left.'],'Bà ở tầng hai, cầu thang bên trái.','Second floor; stairs on the left.'),
(['Anh đã ngắt điện chưa?','Máy số 2 có còn chạy không?'],['Have you disconnected the power?','Is machine number 2 still running?'],'Điện đã ngắt chưa, máy 2 còn chạy không?','Is the power off, and is machine 2 still running?'),
(['Dừng băng chuyền.','Không khởi động lại khi chưa được phép.'],['Stop the conveyor.','Do not restart it without permission.'],'Dừng băng chuyền và chỉ khởi động lại khi được phép.','Halt the conveyor; restart only with permission.'),
(['Nhớ mang hộ chiếu.','Quầy làm thủ tục đóng lúc tám giờ.'],['Remember your passport.','Check-in closes at eight.'],'Mang hộ chiếu, làm thủ tục trước tám giờ.','Bring your passport; check in before eight.'),
(['Tôi muốn đổi lịch hẹn.','Chiều thứ Sáu tôi không có mặt.'],['I want to reschedule my appointment.',"I am unavailable on Friday afternoon."],'Tôi cần đổi hẹn vì chiều thứ Sáu vắng mặt.','Please reschedule; Friday afternoon is unavailable.'),
(['Đóng van cấp nước.','Mở cửa thoát hiểm.'],['Close the water supply valve.','Open the emergency exit.'],'Khóa van nước và mở lối thoát hiểm.','Shut the water valve and open the emergency exit.'),
(['Đội mũ bảo hộ.','Gọi tổ trưởng để xin phép vào.'],['Wear a hard hat.','Call the team leader for permission to enter.'],'Đội mũ, gọi tổ trưởng xin phép vào.','Put on a hard hat and phone the team leader for entry clearance.'),
]

def read(p):return [json.loads(l) for l in p.read_text().splitlines() if l.strip()]
def write(p,rows):p.write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows))
def main():
 p=argparse.ArgumentParser();p.add_argument('command',choices=['prepare','diagnose']);p.add_argument('--root',type=Path,required=True);p.add_argument('--prior',type=Path);p.add_argument('--cli',type=Path);p.add_argument('--tests',type=Path);p.add_argument('--json-jar',type=Path);p.add_argument('--java',default='java');args=p.parse_args();root=args.root
 if args.command=='prepare':
  root.mkdir(parents=True,exist_ok=True)
  if (root/'FREEZE.json').exists():raise FileExistsError('Already frozen')
  parents=[];controls=[]
  for n,(vi,en,compact_vi,compact_en) in enumerate(PAIRS,1):
   for lang,source,target,compact in [('vi',vi,en,compact_en),('en',en,vi,compact_vi)]:
    id=f'COV-{n:02}-{lang}';r=dict(id=id,source_lang=lang,target_lang='en' if lang=='vi' else 'vi',source_text=' '.join(source),reference_target=' '.join(target),segments=source,reference_segments=target,review_status='AI authored before outputs; human_verified=false',semantic_parent=f'COV-{n:02}')
    parents.append(r)
    filler=' Thank you for your patience and your continued understanding.' if lang=='vi' else ' Cảm ơn sự kiên nhẫn và thông cảm của bạn trong suốt thời gian qua.'
    variants=[('full','good',' '.join(target)),('compact','good',compact),('missing','bad',target[0]),('missing_padded','bad',target[0]+filler),('phrase_loop','bad',' '.join([target[0]]*3)),('blank','bad','')]
    for kind,label,text in variants:controls.append(dict(r,id=id+'::'+kind,parent_id=id,error_type=kind,label=label,target_text=text,expected_missing_units=[target[1]] if kind.startswith('missing') else []))
  write(root/'parents.jsonl',parents);write(root/'controls.jsonl',controls)
  actual=[]
  for model in ['envit5','vinai']:
   for split,suffix in [('dev','dev100'),('test','test100-round2')]:
    refs={r['id']:r for r in read(args.prior/'dataset'/f'{split}.jsonl')}
    for r in read(args.prior/'runs'/f'{model}-b2-{suffix}'/'results.jsonl'):
     assert r['id'] in refs and r['source_text']==refs[r['id']]['source_text']
     actual.append(dict(id=model+'::'+r['id'],parent_id=r['id'],model=model,source_lang=r['source_lang'],target_lang=r['target_lang'],source_text=r['source_text'],target_text=r['hypothesis'],reference_target=refs[r['id']]['reference_target'],latency_ms=r['latency_ms'],label='unreviewed_actual_output'))
  assert len(actual)==400;write(root/'actual.jsonl',actual)
  (root/'FREEZE.json').write_text(json.dumps(dict(parents=40,semantic_pairs=20,controls=240,actual=400,reference_status='AI drafts; no human review',hashes={f:hashlib.sha256((root/f).read_bytes()).hexdigest() for f in ['parents.jsonl','controls.jsonl','actual.jsonl']},scope='synthetic controls distinct from actual outputs; diagnostics only; no punctuation changes'),indent=2)+'\n')
  print('Frozen: 240 controls, 400 actual outputs')
 else:
  cp=':'.join(str(x) for x in [args.cli,args.tests,args.json_jar]);cmd=[args.java,'-cp',cp,'CoverageCliKt']
  for filename in ['controls','actual']:
   rows=read(root/(filename+'.jsonl'));result=subprocess.run(cmd,input=''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows),text=True,capture_output=True,check=True)
   results=[json.loads(l) for l in result.stdout.splitlines()];assert len(results)==len(rows) and [r['id'] for r in results]==[r['id'] for r in rows]
   write(root/(filename+'-diagnostics.jsonl'),[dict(r,diagnostic=d) for r,d in zip(rows,results)])
  rows=read(root/'controls-diagnostics.jsonl')
  for detector in ['baseline_failure','hard_failure','suspect']:
   predicted=lambda r:r['diagnostic']['hard_failure'] or bool(r['diagnostic']['missing_units']) if detector=='suspect' else r['diagnostic'][detector]
   tp=sum(r['label']=='bad' and predicted(r) for r in rows);fp=sum(r['label']=='good' and predicted(r) for r in rows);fn=sum(r['label']=='bad' and not predicted(r) for r in rows);tn=sum(r['label']=='good' and not predicted(r) for r in rows)
   print(detector,dict(tp=tp,fp=fp,fn=fn,tn=tn,precision=tp/(tp+fp) if tp+fp else None,recall=tp/(tp+fn)))
  rows=read(root/'actual-diagnostics.jsonl')
  for model in ['envit5','vinai']:
   subset=[r for r in rows if r['model']==model];print(model,dict(n=200,hard=sum(r['diagnostic']['hard_failure'] for r in subset),missing_cues=sum(bool(r['diagnostic']['missing_units']) for r in subset),retryable=sum(bool(r['diagnostic']['retry_sentences']) for r in subset)))
if __name__=='__main__':main()
