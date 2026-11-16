#!/usr/bin/env python3
"""Strict BTC200 VPS CPU functional scorer. No inference, transcript correction or playback."""
from __future__ import annotations
import argparse
from collections import Counter
import csv
import hashlib
import importlib.metadata
import json
import math
from pathlib import Path
import re
import statistics
import wave

DECISIONS={'ModelTranslation','MemoryTranslation','VerifiedTranslation','RepairedTranslation','AskRepeat','NoSpeech'}
ACCEPTED={'ModelTranslation','MemoryTranslation','VerifiedTranslation'}
EXPECTED=200


def normalize(value):
    return ' '.join(re.findall(r'[^\W_]+',value.casefold()))


def text(value):
    return value if isinstance(value,str) else ''


def edit_distance(a,b):
    previous=list(range(len(b)+1))
    for i,x in enumerate(a,1):
        current=[i]
        for j,y in enumerate(b,1):
            current.append(min(current[-1]+1,previous[j]+1,previous[j-1]+(x!=y)))
        previous=current
    return previous[-1]


def sha256(path):
    digest=hashlib.sha256()
    with Path(path).open('rb') as handle:
        for chunk in iter(lambda:handle.read(1024*1024),b''):digest.update(chunk)
    return digest.hexdigest()


class Metrics:
    def __init__(self):
        self.versions={name:importlib.metadata.version(name) for name in ['sacrebleu','whisper-normalizer']}
        if self.versions!={'sacrebleu':'2.5.1','whisper-normalizer':'1.0.1'}:
            raise ValueError('Require existing frozen sacrebleu2.5.1 / whisper-normalizer1.0.1; no fallback')
        from sacrebleu.metrics import CHRF
        from whisper_normalizer.english import EnglishTextNormalizer
        self.metric=CHRF(word_order=0)
        self.english=EnglishTextNormalizer()

    def chrf(self,hyps,refs):
        if len(hyps)!=len(refs):raise ValueError('unpaired chrF inputs')
        score=self.metric.corpus_score(hyps,[refs]).score if refs else None
        return dict(score=score,signature=str(self.metric.get_signature()) if refs else None,
                    samples=len(refs),nonempty_hypotheses=sum(bool(h.strip()) for h in hyps))


def load_jsonl(path):
    rows=[]
    for line_no,line in enumerate(Path(path).read_text(encoding='utf-8').splitlines(),1):
        if not line.strip():continue
        row=json.loads(line)
        if not isinstance(row,dict):raise ValueError(f'{path}:{line_no}: expected object')
        rows.append(row)
    return rows


def validate_manifest(rows):
    if len(rows)!=EXPECTED:raise ValueError('Manifest must contain exactly 200 samples')
    indexed={}
    for row in rows:
        sid=row.get('id')
        if not isinstance(sid,str) or not sid.strip() or sid in indexed:raise ValueError('empty/duplicate manifest id')
        if (row.get('source_lang'),row.get('target_lang')) not in [('en','vi'),('vi','en')]:raise ValueError('invalid direction')
        for key in ('reference_source','reference_target'):
            if not isinstance(row.get(key),str) or not row[key].strip():raise ValueError(f'{sid}: expected frozen {key}')
        if not re.fullmatch('[0-9a-f]{64}',text(row.get('sha256'))):raise ValueError('manifest requires input SHA256')
        indexed[sid]=row
    if Counter(row['source_lang'] for row in rows)!={'en':100,'vi':100}:raise ValueError('Require100VI+100EN')
    return indexed


def index_results(rows,manifest):
    indexed={}
    for row in rows:
        sid=row.get('id')
        if sid not in manifest or sid in indexed:raise ValueError(f'duplicate/unexpected result id: {sid}')
        if row.get('type','sample')!='sample':raise ValueError('unsupported result row type')
        for key in ('source_lang','target_lang'):
            if row.get(key)!=manifest[sid][key]:raise ValueError(f'{sid}: direction mismatch')
        for key in ('raw_asr','raw_mt','final_text','failure','runtime_failure','decision_reason','draft_failure'):
            if row.get(key) is not None and not isinstance(row[key],str):raise ValueError(f'{sid}: {key} must be text/null')
        for key in ('translation_audio_generated','awaiting_confirmation','tentative_output_enabled'):
            if key in row and type(row[key]) is not bool:raise ValueError(f'{sid}: invalid {key}')
        if row.get('tts') is not None and not isinstance(row['tts'],dict):raise ValueError(f'{sid}: invalid tts')
        value=row.get('tentative_output')
        if value is not None:
            if not isinstance(value,dict):raise ValueError(f'{sid}: invalid draft object')
            for key in ('source_text','translated_text','raw_source_text','unavailable_reason'):
                if value.get(key) is not None and not isinstance(value[key],str):raise ValueError(f'{sid}: invalid draft {key}')
        indexed[sid]=row
    return indexed


def audio_audit(event):
    try:
        path=Path(event['wav'])
        if not re.fullmatch('[0-9a-f]{64}',text(event.get('sha256'))) or sha256(path)!=event['sha256']:
            raise ValueError('hash mismatch')
        if type(event.get('samples')) is not int or event['samples']<=0:raise ValueError('invalid sample count')
        if type(event.get('sample_rate')) is not int or not 8000<=event['sample_rate']<=192000:raise ValueError('invalid rate')
        with wave.open(str(path),'rb') as wav:
            if (wav.getnchannels(),wav.getsampwidth(),wav.getframerate(),wav.getnframes())!=(1,2,event['sample_rate'],event['samples']):
                raise ValueError('PCM16 header mismatch')
            if len(wav.readframes(event['samples']+1))!=event['samples']*2:raise ValueError('truncated WAV payload')
        return dict(path=str(path.resolve()),sha256=event['sha256'],valid=True,sample_rate=event['sample_rate'],samples=event['samples'])
    except (KeyError,TypeError,OSError,ValueError,EOFError,wave.Error) as error:
        return dict(path=text(event.get('wav')),valid=False,error=f'{type(error).__name__}: {error}')


def inspect_policy(source,row):
    issues=[]
    def issue(reason,detail=None):issues.append(dict(id=source['id'],reason=reason,detail=detail))
    if not row:
        issue('missing_result');return issues,None
    for key in ('failure','runtime_failure','draft_failure'):
        if row.get(key):issue('declared_failure',key+': '+row[key])
    if row.get('input_sha256')!=source['sha256']:issue('input_hash_mismatch')
    for key in ('reference_source','reference_target'):
        if key in row and row[key]!=source[key]:issue('reference_drift',key)
    decision=row.get('decision');calls=row.get('mt_calls')
    if decision not in DECISIONS:issue('missing_or_invalid_terminal_decision',decision)
    if type(calls) is not int or calls<0:issue('invalid_mt_call_count')
    elif calls>1:issue('extra_mt_call',str(calls))
    latency=row.get('pipeline_to_audio_ready_ms')
    if type(latency) not in (int,float) or not math.isfinite(latency) or latency<0:issue('invalid_pipeline_latency')
    event=row.get('tts');accepted=decision in ACCEPTED
    if accepted:
        if not text(row.get('final_text')).strip() or row.get('translation_audio_generated') is not True:issue('accepted_missing_text_or_audio')
        if not event or event.get('cached_prompt') is not False:issue('accepted_missing_translation_tts')
    elif decision in ('AskRepeat','NoSpeech'):
        if text(row.get('final_text')).strip():issue('rejected_final_text')
        if row.get('translation_audio_generated') is not False:issue('rejected_translation_audio')
        if not event or event.get('cached_prompt') is not True:issue('rejected_tts_not_cached_prompt')
    elif decision=='RepairedTranslation':
        if event or row.get('translation_audio_generated'):issue('repair_spoken_before_confirmation')
        if row.get('awaiting_confirmation') is not True:issue('repair_missing_confirmation_flag')
    draft=row.get('tentative_output')
    if draft is not None:
        if decision!='AskRepeat':issue('draft_on_non_repeat')
        if draft.get('whole_utterance_uncertain') is not True:issue('draft_missing_uncertainty_warning')
        if not text(draft.get('source_text')).strip():issue('draft_missing_source')
        if len(text(draft.get('source_text')))>2000 or len(text(draft.get('translated_text')))>4000:issue('draft_display_bound_exceeded')
        raw=text(draft.get('raw_source_text'))
        if raw and not text(row.get('raw_asr')).startswith(raw):issue('draft_raw_source_drift')
    audit=None
    if event:
        if event.get('ok') is not True:issue('tts_not_ok')
        if type(event.get('cached_prompt')) is not bool:issue('tts_missing_cache_identity')
        audit=audio_audit(event)
        if not audit['valid']:issue('tts_audio_invalid',audit['error'])
    return issues,audit


def wer_pairs(pairs,normalizer):
    edits=units=empty=0
    for reference,hypothesis in pairs:
        ref=normalizer(reference).split();hyp=normalizer(hypothesis).split()
        edits+=edit_distance(ref,hyp);units+=len(ref);empty+=not bool(hyp)
    return dict(samples=len(pairs),word_edits=edits,reference_words=units,empty_hypotheses=empty,
                wer=edits/units if units else None)


def asr_groups(manifest,indexed,normalizer,accepted_only=False):
    def pairs(rows):
        return [(m['reference_source'],text(indexed.get(m['id'],{}).get('raw_asr'))
                 if not accepted_only or indexed.get(m['id'],{}).get('decision') in ACCEPTED else '') for m in rows]
    return {label:wer_pairs(pairs(rows),normalizer) for label,rows in
            [('all',manifest),('vi',[m for m in manifest if m['source_lang']=='vi']),
             ('en',[m for m in manifest if m['source_lang']=='en'])]}


def display_candidate(row):
    """Display diagnostic only: this never grants acceptance, speech, or learning permission."""
    final=text(row.get('final_text'))
    if row.get('decision') in ACCEPTED and row.get('translation_audio_generated') is True and final.strip():
        return final,'accepted_final'
    if row.get('decision')=='AskRepeat':
        candidate=text((row.get('tentative_output') or {}).get('translated_text'))
        if candidate.strip():return candidate,'UNTRUSTED_DRAFT'
    return '','empty'


def mt_metrics(manifest,indexed,metrics):
    accepted=[m for m in manifest if indexed.get(m['id'],{}).get('decision') in ACCEPTED
              and indexed.get(m['id'],{}).get('translation_audio_generated') is True
              and text(indexed.get(m['id'],{}).get('final_text')).strip()]
    def score(rows,key,final=False):
        hyps=[text(indexed.get(m['id'],{}).get(key))
              if not final or m in accepted else '' for m in rows]
        return metrics.chrf(hyps,[m['reference_target'] for m in rows])
    candidates=[display_candidate(indexed.get(m['id'],{})) for m in manifest]
    display_score=metrics.chrf([candidate for candidate,_ in candidates],[m['reference_target'] for m in manifest])
    display_score.update(coverage=sum(bool(candidate.strip()) for candidate,_ in candidates)/len(manifest),
        accepted_final_samples=sum(kind=='accepted_final' for _,kind in candidates),
        untrusted_draft_samples=sum(kind=='UNTRUSTED_DRAFT' for _,kind in candidates),
        includes_untrusted_drafts=True,grants_acceptance_or_speech=False,
        scope='DISPLAY ONLY includes UNTRUSTED drafts; accepted final else AskRepeat draft else empty; all200 references retained')
    return dict(final_all200=score(manifest,'final_text',True),
                best_effort_display_MT=display_score,
                final_by_direction={lang:score([m for m in manifest if m['source_lang']==lang],'final_text',True) for lang in ('vi','en')},
                accepted_subset=score(accepted,'final_text',True),raw_mt_all200=score(manifest,'raw_mt'),
                final_policy='Every200reference included; rejected/missing final output is empty; draft never substituted',
                accepted_subset_warning='Selected by terminal permission; cannot compare subset chrF as all200 quality')


def draft_metrics(manifest,indexed,metrics):
    rejected=[m for m in manifest if indexed.get(m['id'],{}).get('decision')=='AskRepeat']
    drafts=[indexed[m['id']].get('tentative_output') or {} for m in rejected]
    hyps=[text(d.get('translated_text')) for d in drafts]
    source_pairs=[(m['reference_source'],text(d.get('raw_source_text') or d.get('source_text'))) for m,d in zip(rejected,drafts)]
    return dict(rejected_samples=len(rejected),source_available=sum(bool(text(d.get('source_text')).strip()) for d in drafts),
                target_available=sum(bool(h.strip()) for h in hyps),
                source=wer_pairs(source_pairs,normalize),target=metrics.chrf(hyps,[m['reference_target'] for m in rejected]),
                scope='ASK_REPEAT diagnostic only; missing draft=empty within every rejected reference; never accepted output',
                examples=[dict(id=m['id'],direction=m['source_lang']+'-'+m['target_lang'],
                    reason=indexed[m['id']].get('decision_reason'),raw_asr=indexed[m['id']].get('raw_asr'),
                    reference_source=m['reference_source'],reference_target=m['reference_target'],
                    tentative_output=indexed[m['id']].get('tentative_output'),final_text=indexed[m['id']].get('final_text'),
                    tts_cached_prompt=(indexed[m['id']].get('tts') or {}).get('cached_prompt')) for m in rejected])


def aggregate(manifest,rows,metrics):
    expected=validate_manifest(manifest);indexed=index_results(rows,expected)
    issues=[];audits=[]
    for source in manifest:
        found,audit=inspect_policy(source,indexed.get(source['id'],{}));issues.extend(found)
        if audit:audits.append(dict(id=source['id'],**audit))
    paths=Counter(a['path'] for a in audits)
    issues.extend(dict(id=None,reason='reused_tts_wav_path',detail=path) for path,count in paths.items() if count>1)
    accepted=lambda r:r.get('decision') in ACCEPTED and r.get('translation_audio_generated') is True and bool(text(r.get('final_text')).strip())
    times=[r['pipeline_to_audio_ready_ms'] for r in rows if type(r.get('pipeline_to_audio_ready_ms')) in (int,float)
           and math.isfinite(r['pipeline_to_audio_ready_ms']) and not r.get('failure')]
    norm_en=[(m['reference_source'],text(indexed.get(m['id'],{}).get('raw_asr'))) for m in manifest if m['source_lang']=='en']
    return dict(scope='VPS CPU functional WAV-to-policy-authorized WAV ready; no microphone or speaker playback',
        official_score=False,human_acceptance=False,qnn_latency_claim=False,complete=not issues,
        versions=metrics.versions,coverage=dict(expected=EXPECTED,present=len(indexed),missing=EXPECTED-len(indexed)),
        decisions=dict(Counter(indexed.get(m['id'],{}).get('decision','MISSING') for m in manifest)),
        decision_reasons=dict(Counter(r['decision_reason'] for r in rows if r.get('decision_reason'))),
        accepted_translation_samples=sum(accepted(r) for r in rows),
        asr=dict(raw_all_inputs=asr_groups(manifest,indexed,normalize),
                 en_normalized_all_inputs=wer_pairs(norm_en,metrics.english),
                 accepted_path_rejects_empty=asr_groups(manifest,indexed,normalize,True),
                 normalization='raw lexical: casefold Unicode letters/digits whitespace, no number-word equivalence; EN normalized: pinned whisper-normalizer1.0.1',
                 raw_policy='All200 inputs retained; actual raw_asr preserved even rejected; missing/absent raw output=empty. Separate accepted-path metric blanks rejects.'),
        mt=mt_metrics(manifest,indexed,metrics),drafts=draft_metrics(manifest,indexed,metrics),
        tts=dict(recorded_wavs=len(audits),verified_wavs=sum(a['valid'] for a in audits),
                 translation_samples=sum(r.get('translation_audio_generated') is True for r in rows),
                 cached_prompt_samples=sum((r.get('tts') or {}).get('cached_prompt') is True for r in rows),
                 audio_artifacts=audits,playback_performed=False),
        latency=dict(samples=len(times),p50_ms=statistics.median(times) if times else None,
                     p95_ms=sorted(times)[math.ceil(.95*len(times))-1] if times else None,
                     total_s=sum(times)/1000,scope='Loaded VPS file-to-complete WAV including cold loads; no Snapdragon/QNN latency inference'),
        references=dict(count=len(manifest),declared_status='Existing frozen AI/unreviewed drafts; no independent fresh human acceptance',
                        human_verified_count=sum(m.get('human_verified') is True for m in manifest),
                        final_provenance_labels=dict(Counter(text(m.get('reference_target_provenance')) or 'unknown/unreviewed' for m in manifest)),
                        metadata_labels=dict(Counter(text(m.get('translation_source_metadata')) or 'missing' for m in manifest)),
                        note='Metadata human/AI labels are not inherited by Final target references'),
        models=dict(asr=dict(Counter(text(r.get('asr_model')) or 'not_decoded' for r in rows)),
                    backend=dict(Counter(text(r.get('asr_backend')) or 'not_recorded' for r in rows)),
                    mt=dict(Counter(text(r.get('mt_model')) or 'not_recorded' for r in rows))),issues=issues)


def report_markdown(summary):
    a=summary['asr'];d=summary['drafts'];m=summary['mt']
    fmt=lambda x:'N/A' if x is None else f'{x:.2%}'
    score=lambda x:'N/A' if x is None else f'{x:.2f}'
    lines=['# BTC200 — VPS full pipeline với draft ASK_REPEAT','',
        f"Coverage: {summary['coverage']['present']}/200; complete={summary['complete']}; issues={len(summary['issues'])}.",
        'CPU functional: WAV→VAD→ASR thật→EnViT5→core safety/policy→Piper/cached prompt WAV. Không phát loa, không đo điện thoại/QNN.',
        'Reference là frozen AI/unreviewed draft; không phải human acceptance. Nhãn human/AI ở Metadata không được chuyển sang Final.', '',
        '| Metric | Kết quả |','|---|---:|',
        f"| Accepted translation | {summary['accepted_translation_samples']}/200 |",
        f"| Decisions | {json.dumps(summary['decisions'],ensure_ascii=False)} |",
        f"| Raw lexical ASR WER all200 | {fmt(a['raw_all_inputs']['all']['wer'])} |",
        f"| Raw lexical ASR WER VI100 | {fmt(a['raw_all_inputs']['vi']['wer'])} |",
        f"| Raw lexical ASR WER EN100 | {fmt(a['raw_all_inputs']['en']['wer'])} |",
        f"| EN normalized WER all100 | {fmt(a['en_normalized_all_inputs']['wer'])} |",
        f"| Accepted-path ASR WER all200 (reject=rỗng) | {fmt(a['accepted_path_rejects_empty']['all']['wer'])} |",
        f"| Final MT chrF all200 (reject=rỗng) | {score(m['final_all200']['score'])} |",
        f"| Best-effort DISPLAY MT chrF all200 — gồm UNTRUSTED drafts | {score(m['best_effort_display_MT']['score'])} |",
        f"| DISPLAY coverage / untrusted draft samples | {m['best_effort_display_MT']['nonempty_hypotheses']}/200 ({fmt(m['best_effort_display_MT']['coverage'])}) / {m['best_effort_display_MT']['untrusted_draft_samples']} |",
        f"| Accepted-subset MT chrF / n | {score(m['accepted_subset']['score'])} / {m['accepted_subset']['samples']} |",
        f"| Raw MT chrF all200 | {score(m['raw_mt_all200']['score'])} |",
        f"| TTS verified WAVs / cached prompts | {summary['tts']['verified_wavs']} / {summary['tts']['cached_prompt_samples']} |",
        f"| VPS audio-ready p50/p95 ms | {score(summary['latency']['p50_ms'])}/{score(summary['latency']['p95_ms'])} |",'',
        'Raw ASR luôn dùng output thực, không cắt hay thay bằng draft; absent/missing vẫn nằm trong mẫu số dưới dạng rỗng. '
        'Accepted-path metric riêng đặt rejects=rỗng. Accepted-subset chrF không được so như chất lượng toàn200.', '',
        'Best-effort DISPLAY MT dùng accepted final khi có; nếu AskRepeat thì dùng tentative target; còn lại rỗng. '
        'Metric giữ đủ200 references và **bao gồm UNTRUSTED drafts**. Nó chỉ đo candidate có thể hiển thị khi yêu cầu nói lại, '
        'không đổi accepted decisions/final score, không cấp quyền TTS hay learning.', '',
        '## Draft riêng cho ASK_REPEAT','',
        f"ASK_REPEAT={d['rejected_samples']}; có source={d['source_available']}; có target={d['target_available']}. "
        f"Source WER trên toàn bộ rejected references={fmt(d['source']['wer'])}; target chrF={score(d['target']['score'])}, n={d['target']['samples']}.",
        'Draft không thay thế final_text, không được phát TTS, không làm ASK_REPEAT thành accepted. Draft vắng được chấm rỗng trong subset rejected.', '',
        '| ID | Reason | ASR | Draft target | Cached prompt |','|---|---|---|---|---|']
    clean=lambda s:' '.join(text(s).split()).replace('|','\\|')
    for row in d['examples']:
        lines.append(f"| {row['id']} | {clean(row['reason'])} | {clean(row['raw_asr'])} | {clean((row['tentative_output'] or {}).get('translated_text'))} | {row['tts_cached_prompt']} |")
    lines+=['','## Phạm vi so sánh','',
        'Baseline VPS EnViT5 dùng English Whisper Small; lượt mới dùng English Parakeet TDT v2. '
        'Khác ASR, ngày, source snapshot và tải VPS; không quy delta chất lượng/latency riêng cho reliability guard hay draft. '
        'Không có kết luận QNN parity, Snapdragon latency, microphone/speaker hoặc human-reviewed translation quality.', '',
        'Xem `summary.json` cho hash/header của từng TTS WAV, mọi lỗi khai báo, decision reasons, model/backend và metric signatures. '
        '`sample-results.csv` chứa đủ200 câu và giữ nguyên raw ASR/source.','']
    if summary.get('baseline_comparison'):
        baseline=summary['baseline_comparison']['baseline']
        lines+=['## Baseline cùng manifest (so riêng, không quy cho draft)', '',
            '| Metric | Baseline | Current |','|---|---:|---:|',
            f"| Coverage | {baseline['coverage']['present']}/200 | {summary['coverage']['present']}/200 |",
            f"| Accepted | {baseline['accepted_translation_samples']} | {summary['accepted_translation_samples']} |",
            f"| Raw ASR WER all200 | {fmt(baseline['asr']['raw_all_inputs']['all']['wer'])} | {fmt(a['raw_all_inputs']['all']['wer'])} |",
            f"| EN normalized WER | {fmt(baseline['asr']['en_normalized_all_inputs']['wer'])} | {fmt(a['en_normalized_all_inputs']['wer'])} |",
            f"| Final chrF all200 | {score(baseline['mt']['final_all200']['score'])} | {score(m['final_all200']['score'])} |",
            f"| DISPLAY chrF all200 (gồm UNTRUSTED drafts) | {score(baseline['mt']['best_effort_display_MT']['score'])} | {score(m['best_effort_display_MT']['score'])} |", '',
            f"Decision changes: {len(summary['baseline_comparison']['decision_changes'])}; baseline complete={baseline['complete']}.",
            summary['baseline_comparison']['warning'], '']
    return '\n'.join(lines)


def write_outputs(out,summary,manifest,rows):
    out=Path(out);out.mkdir(parents=True,exist_ok=False)
    (out/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,allow_nan=False,indent=2)+'\n',encoding='utf-8')
    (out/'report.md').write_text(report_markdown(summary),encoding='utf-8')
    indexed={r['id']:r for r in rows}
    fields=['id','source_lang','target_lang','decision','reason','reference_source','raw_asr','reference_target','raw_mt','final_text','draft_source','draft_raw_source','draft_target','best_effort_display_MT','display_trust','mt_calls','translation_audio_generated','tts_cached_prompt','tts_wav','failure']
    with (out/'sample-results.csv').open('w',newline='',encoding='utf-8-sig') as handle:
        writer=csv.DictWriter(handle,fieldnames=fields);writer.writeheader()
        for source in manifest:
            row=indexed.get(source['id'],{});draft=row.get('tentative_output') or {};tts=row.get('tts') or {}
            writer.writerow(dict(id=source['id'],source_lang=source['source_lang'],target_lang=source['target_lang'],
                decision=row.get('decision','MISSING'),reason=row.get('decision_reason'),reference_source=source['reference_source'],
                raw_asr=row.get('raw_asr'),reference_target=source['reference_target'],raw_mt=row.get('raw_mt'),final_text=row.get('final_text'),
                draft_source=draft.get('source_text'),draft_raw_source=draft.get('raw_source_text'),draft_target=draft.get('translated_text'),
                best_effort_display_MT=display_candidate(row)[0],display_trust=display_candidate(row)[1],
                mt_calls=row.get('mt_calls'),translation_audio_generated=row.get('translation_audio_generated'),
                tts_cached_prompt=tts.get('cached_prompt'),tts_wav=tts.get('wav'),failure=row.get('failure')))


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest',type=Path,required=True);parser.add_argument('--results',type=Path,required=True)
    parser.add_argument('--out-dir',type=Path,required=True)
    parser.add_argument('--baseline',type=Path,help='Optional same-manifest historical VPS results; separately audited')
    args=parser.parse_args(argv)
    try:
        manifest=load_jsonl(args.manifest);rows=load_jsonl(args.results)
        metrics=Metrics()
        summary=aggregate(manifest,rows,metrics)
        if args.baseline:
            baseline_rows=load_jsonl(args.baseline)
            baseline=aggregate(manifest,baseline_rows,metrics)
            summary['baseline_comparison']=dict(
                warning='Historical baseline EN Whisper Small versus current EN Parakeet v2; differing ASR/source/host load, no draft-only attribution',
                baseline_results=dict(path=str(args.baseline.resolve()),sha256=sha256(args.baseline)),
                baseline=baseline,decision_changes=[dict(id=m['id'],baseline={r['id']:r for r in baseline_rows}.get(m['id'],{}).get('decision'),
                    candidate={r['id']:r for r in rows}.get(m['id'],{}).get('decision')) for m in manifest
                    if {r['id']:r for r in baseline_rows}.get(m['id'],{}).get('decision')!={r['id']:r for r in rows}.get(m['id'],{}).get('decision')])
        summary['input_files']={label:dict(path=str(path.resolve()),sha256=sha256(path)) for label,path in
            [('manifest',args.manifest),('results',args.results),('scorer',Path(__file__))]}
        write_outputs(args.out_dir,summary,manifest,rows)
    except (OSError,ValueError,ImportError,importlib.metadata.PackageNotFoundError) as error:parser.error(str(error))
    print(json.dumps(dict(output=str(args.out_dir.resolve()),coverage=summary['coverage'],complete=summary['complete'],
                         issues=len(summary['issues']),decisions=summary['decisions']),ensure_ascii=False))
    return 0 if summary['complete'] else 1

if __name__=='__main__':raise SystemExit(main())
