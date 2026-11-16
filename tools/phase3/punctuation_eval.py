#!/usr/bin/env python3
"""Freeze paired synthetic VI inputs using the production Kotlin restorer; never edits references."""
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import argparse

CONTROLS = [
    ("Anh đã khóa nguồn điện chưa?", "Have you disconnected the power?"),
    ("Áp suất cho phép là bao nhiêu?", "What is the permitted pressure?"),
    ("Van A-12 đang ở đâu?", "Where is valve A-12?"),
    ("Máy số 3 còn điện không?", "Is machine number 3 still powered?"),
    ("Anh cần cờ lê hay tua vít?", "Do you need a wrench or a screwdriver?"),
    ("Ai đang điều khiển cần cẩu?", "Who is operating the crane?"),
    ("Tại sao máy số 2 dừng?", "Why has machine number 2 stopped?"),
    ("Khi nào được tháo giàn giáo?", "When can the scaffolding be dismantled?"),
    ("Có cần đeo kính bảo hộ không?", "Do we need to wear safety goggles?"),
    ("Khoảng cách an toàn là mấy mét?", "How many meters is the safe distance?"),
    ("Không bật máy số 3.", "Do not turn on machine number 3."),
    ("Đừng mở van A-12.", "Do not open valve A-12."),
    ("Cấm đứng dưới cần cẩu.", "Do not stand under the crane."),
    ("Hãy hỏi xem máy số 3 đã tắt chưa.", "Ask whether machine number 3 has been turned off."),
    ("Tôi không rõ van nào đã khóa.", "I am not sure which valve has been closed."),
    ("Chưa được tháo giàn giáo.", "The scaffolding must not be dismantled yet."),
    ("Không có điện.", "There is no power."),
    ("Áp suất là 3.5 bar.", "The pressure is 3.5 bar."),
    ("Hãy kiểm tra xem có rò khí không.", "Check whether there is a gas leak."),
    ("Tôi không biết ai đang giữ chìa khóa.", "I do not know who has the key."),
    ("Bất kỳ ai cũng phải đội mũ.", "Everyone must wear a helmet."),
    ("Hãy chọn loại nào phù hợp.", "Choose a suitable type."),
    ("Cờ lê hay tua vít đều được.", "Either a wrench or a screwdriver is fine."),
    ("Tôi hỏi anh ấy máy có điện không.", "I asked him whether the machine was powered."),
    ("Không biết khi nào mới xong.", "I do not know when it will be finished."),
    ("Máy nào cũng phải ngắt điện trước khi sửa.", "Every machine must be disconnected from power before repair."),
    ("Tôi nhớ anh đã hỏi van ở đâu.", "I remember you asked where the valve was."),
    ("Nếu có điện thì không chạm vào.", "If the power is on, do not touch it."),
    ("Đúng, tôi đã kiểm tra rồi.", "Yes, I have checked it."),
    ("Tôi muốn xác nhận máy có điện không.", "I want to confirm whether the machine is powered."),
]

def write(path, rows):
    path.write_text(''.join(json.dumps(r, ensure_ascii=False)+'\n' for r in rows))

def main():
    p=argparse.ArgumentParser();p.add_argument('--dataset',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--jar',type=Path,required=True);args=p.parse_args()
    args.out.mkdir(parents=True,exist_ok=True)
    if (args.out/'FREEZE.json').exists(): raise FileExistsError('Dataset already frozen; use a new directory')
    rows=[]
    for split in ['dev','test']:
        rows.extend(dict(r, parent_split=split, group='btc') for r in map(json.loads,(args.dataset/(split+'.jsonl')).read_text().splitlines()) if r['source_lang']=='vi')
    for i,(source,ref) in enumerate(CONTROLS,1):
        rows.append(dict(id=f'CTRL-{i:02}',source_lang='vi',target_lang='en',source_text=source,reference_target=ref,reference_target_original=None,original_provenance='ai_authored_control',human_verified=False,parent_split='control',group='control'))
    degraded=[r['source_text'].lower().replace('?','.') for r in rows]
    encoded=''.join(base64.b64encode(t.encode()).decode()+'\n' for t in degraded)
    result=subprocess.run(['/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home/bin/java','-jar',str(args.jar)],input=encoded,text=True,capture_output=True,check=True)
    restored=[base64.b64decode(t).decode() for t in result.stdout.splitlines()]
    assert len(restored)==len(rows)
    paired=[];manifest=[]
    for r,b,a in zip(rows,degraded,restored):
        paired.append(dict(r,degraded=b,restored=a,expected_question=r['source_text'].endswith('?')))
        for variant,text in [('before',b),('after',a)]:
            manifest.append(dict(r,id=r['id']+'::'+variant,parent_id=r['id'],variant=variant,source_text=text,split='dev'))
    write(args.out/'pairs.jsonl',paired);write(args.out/'manifest.jsonl',manifest)
    hashes={f:hashlib.sha256((args.out/f).read_bytes()).hexdigest() for f in ['pairs.jsonl','manifest.jsonl']}
    (args.out/'FREEZE.json').write_text(json.dumps(dict(n_pairs=len(rows),hashes=hashes,restorer_sha256=hashlib.sha256(args.jar.read_bytes()).hexdigest(),degradation='lowercase + replace all question marks with periods; synthetic, not actual ASR',reference_status='existing frozen BTC AI drafts plus independent AI-authored controls; no human review'),indent=2)+'\n')
    for group in ['btc','control']:
        subset=[r for r in paired if r['group']==group]
        tp=sum(r['expected_question'] and r['restored'].endswith('?') for r in subset)
        fp=[r['id'] for r in subset if not r['expected_question'] and r['restored'].endswith('?')]
        fn=[r['id'] for r in subset if r['expected_question'] and not r['restored'].endswith('?')]
        print(group,dict(n=len(subset),tp=tp,fp=fp,fn=fn))

if __name__=='__main__': main()
