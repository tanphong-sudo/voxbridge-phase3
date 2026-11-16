#!/usr/bin/env python3
"""Freeze a punctuation ablation suite before looking at model predictions."""
import hashlib
import json
from pathlib import Path

# Independent AI-authored text/reference drafts. Not human-verified acceptance labels.
FRESH=[
('Bạn có thể ngắt cầu dao trước khi bảo trì không?','Can you switch off the circuit breaker before maintenance?'),
('Chị kiểm tra dây tiếp đất xong chưa?','Have you finished checking the grounding wire?'),
('Bình chữa cháy gần nhất ở đâu?','Where is the nearest fire extinguisher?'),
('Tải trọng tối đa của sàn này là bao nhiêu?','What is the maximum load capacity of this platform?'),
('Cần bao lâu để thay bộ lọc?','How long does it take to replace the filter?'),
('Tại sao đèn cảnh báo vẫn nhấp nháy?','Why is the warning light still flashing?'),
('Có ai còn ở trong hầm không?','Is anyone still in the tunnel?'),
('Ai chịu trách nhiệm khóa tủ điện?','Who is responsible for locking the electrical cabinet?'),
('Anh chọn găng tay cao su hay găng tay cách điện?','Do you choose rubber gloves or electrically insulating gloves?'),
('Van B-07 đã đóng hoàn toàn chưa?','Has valve B-07 been fully closed?'),
('Mình phải gọi số nào khi có sự cố?','Which number should we call in an emergency?'),
('Dùng thang này có an toàn không?','Is it safe to use this ladder?'),
('Làm thế nào để kiểm tra khí độc?','How do we check for toxic gas?'),
('Điện áp đo được là mấy vôn?','How many volts is the measured voltage?'),
('Tháo tấm chắn lúc này được không?','Can the guard be removed now?'),
('Tôi cần đeo loại mặt nạ nào?','What type of mask do I need to wear?'),
('Anh ấy hỏi gì về lối thoát hiểm?','What did he ask about the emergency exit?'),
('Khi nào đội cứu hộ đến?','When will the rescue team arrive?'),
('Nếu áp suất tăng thì có phải dừng máy không?','If the pressure rises, must we stop the machine?'),
('Đây có phải công tắc dừng khẩn cấp không?','Is this the emergency stop switch?'),
('Tôi vừa nói không.','I just said no.'),
('Anh ấy trả lời chưa.','He answered not yet.'),
('Tổ trưởng yêu cầu kiểm tra máy có điện không.','The team leader asked us to check whether the machine was powered.'),
('Chúng tôi cần biết ai còn ở trong hầm.','We need to know who is still in the tunnel.'),
('Nhớ kiểm tra van B-07 đã đóng chưa.','Remember to check whether valve B-07 has been closed.'),
('Bất cứ máy nào cũng cần bảo trì.','Every machine needs maintenance.'),
('Mấy người bên đội điện đang kiểm tra dây.','Some people from the electrical team are checking the wires.'),
('Tôi không nhớ bình chữa cháy ở đâu.','I do not remember where the fire extinguisher is.'),
('Hãy báo cho tôi khi nào đội cứu hộ đến.','Tell me when the rescue team arrives.'),
('Cần tìm hiểu vì sao đèn cảnh báo vẫn sáng.','We need to find out why the warning light is still on.'),
('Đừng hỏi ai đã làm trước khi ngắt điện.','Do not ask who did it before disconnecting the power.'),
('Cấm khởi động bơm khi chưa có nước.','Do not start the pump before water is available.'),
('Áp suất hiện tại là 2.75 bar.','The current pressure is 2.75 bar.'),
('Mã thiết bị là B-07, không phải B-70.','The equipment ID is B-07, not B-70.'),
('Anh thường hỏi máy đã dừng chưa.','You often ask whether the machine has stopped.'),
('Không rõ ai đang giữ chìa khóa.','It is not clear who has the key.'),
('Loại mặt nạ nào cũng phải vừa khuôn mặt.','Every type of mask must fit the face.'),
('Tôi đã xác nhận dây nào bị hỏng.','I have confirmed which wire is damaged.'),
('Tôi chỉ chọn không khi chưa được kiểm tra.','I only choose no when it has not been checked.'),
('Hãy hỏi tổ trưởng xem có cần gọi cứu hộ không.','Ask the team leader whether we need to call rescue services.'),
]
AUDIT_REFS=['I said no.','I choose no.','The label says not yet.','Some men are repairing the machine.','Everyone must wear a helmet.','I remember where the valve is.','I remember which machine stopped.','What did he ask?','Do not open valve A-12.','The pressure is 3.5 bar.']

def read(path):return [json.loads(l) for l in path.read_text().splitlines() if l.strip()]
def main():
    root=Path('output/phase3/punctuation-next');root.mkdir(parents=True,exist_ok=True)
    if (root/'sources.jsonl').exists():raise FileExistsError('Already frozen')
    previous=Path('output/phase3/punctuation')
    rows=[]
    for filename in ['v3/pairs.jsonl','v3/extra-pairs.jsonl']:
        for r in read(previous/filename):
            rows.append(dict(r,input_text=r['degraded'],evaluation_group=r['group'],parent_id=r['id'],input_provenance='synthetic: lowercase + ? replaced with .'))
    for i,(r,ref) in enumerate(zip(read(previous/'strict-rule-audit.jsonl'),AUDIT_REFS),1):
        rows.append(dict(id=f'AUDIT-{i:02}',source_text=r['source'],input_text=r['source'].lower().replace('?','.'),reference_target=ref,evaluation_group='adversarial_exposed',expected_question=r['expected_question'],input_provenance='synthetic adversarial examples exposed to author',source_lang='vi',target_lang='en'))
    for i,(s,ref) in enumerate(FRESH,1):
        rows.append(dict(id=f'FRESH-{i:02}',source_text=s,input_text=s.lower().replace('?','.'),reference_target=ref,evaluation_group='fresh_frozen',expected_question=s.endswith('?'),input_provenance='synthetic; frozen before model outputs; AI authored',source_lang='vi',target_lang='en'))
    refs={r['id']:r for r in rows if r['evaluation_group']=='btc'}
    for r in read(Path('output/model-upgrades-20261015/asr-baseline-btc10.jsonl')):
        parent=refs[r['id']]
        assert parent['source_text']==r['reference_source']
        rows.append(dict(id='ASR-'+r['id'],parent_id=r['id'],source_text=r['reference_source'],input_text=r['text'],reference_target=parent['reference_target'],evaluation_group='actual_asr_snapshot',expected_question=parent['expected_question'],input_provenance='saved PhoWhisper-base output; not latest phone ASR; lexical errors retained',source_lang='vi',target_lang='en'))
    assert len(rows)==210 and len({r['id'] for r in rows})==210
    out=root/'sources.jsonl';out.write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows))
    (root/'FREEZE.json').write_text(json.dumps(dict(n=len(rows),sha256=hashlib.sha256(out.read_bytes()).hexdigest(),reference_status='AI drafts; no human review',variants=['raw','casing','old_rule','neural_full','neural_terminal'],threshold=.90,primary_comparison='paired MT quality and question false positives; no universal correctness claim'),indent=2)+'\n')
    print(dict(n=len(rows),sha256=hashlib.sha256(out.read_bytes()).hexdigest()))
if __name__=='__main__':main()
