import json,base64,subprocess,hashlib
from pathlib import Path
root=Path('output/phase3/punctuation')
old=[json.loads(l) for l in (root/'unseen-rule-diagnostic.jsonl').read_text().splitlines()]
refs=['How can I turn off the machine?','Why is the warning light red?','How many helmets are you bringing?','What tools are needed?','Which valve is open?','Who checked the power supply?','Have you informed the foreman yet?','Is this machine still powered?','Where is there a fire extinguisher?','How many people are on the scaffolding?', 'I need to know where the valve is.','Check whether the machine has been turned off.','Anyone can report a hazard.','I am finding out when entry is allowed.','We are not sure whether the machine is powered.','You often work the night shift.','Do not ask whether the machine has stopped.','Do not open cabinet A-12.','The pressure must not exceed 3.5 bar.','Any of them will work.']
texts=[r['source'].lower().replace('?','.') for r in old]
proc=subprocess.run(['/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home/bin/java','-jar',str(root/'restorer-v3.jar')],input=''.join(base64.b64encode(t.encode()).decode()+'\n' for t in texts),text=True,capture_output=True,check=True)
restored=[base64.b64decode(t).decode() for t in proc.stdout.splitlines()]
rows=[];manifest=[]
for i,(r,b,a,ref) in enumerate(zip(old,texts,restored,refs),1):
 row=dict(id=f'EXTRA-{i:02}',source_lang='vi',target_lang='en',source_text=r['source'],degraded=b,restored=a,expected_question=r['expected_question'],reference_target=ref,reference_target_original=None,original_provenance='ai_authored_extra',human_verified=False,parent_split='extra',group='extra',split='dev')
 rows.append(row)
 for v,t in [('before',b),('after',a)]:manifest.append(dict(row,id=row['id']+'::'+v,source_text=t))
for filename,data in [('extra-pairs.jsonl',rows),('extra-manifest.jsonl',manifest)]: (root/'v3'/filename).write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in data))
assert all(r['expected_question']==r['restored'].endswith('?') for r in rows)
a=[json.loads(l) for l in (root/'v2'/'pairs.jsonl').read_text().splitlines()];b=[json.loads(l) for l in (root/'v3'/'pairs.jsonl').read_text().splitlines()]
assert [(r['id'],r['degraded'],r['restored']) for r in a]==[(r['id'],r['degraded'],r['restored']) for r in b]
(root/'v3'/'reuse-validation.json').write_text(json.dumps(dict(n_pairs_identical=130,reason='Byte-identical before/after MT inputs under v2 and v3 rules; model revisions/settings unchanged',extra_refs_sha256=hashlib.sha256((root/'v3'/'extra-pairs.jsonl').read_bytes()).hexdigest()),indent=2)+'\n')
print('v3: original 130 inputs identical; extra 20 all expected punctuation; extra references frozen before inference')
