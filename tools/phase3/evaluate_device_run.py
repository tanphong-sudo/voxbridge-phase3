#!/usr/bin/env python3
"""Validate declared device timing/PSS logs and frozen budgets; never operate a device."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path
import re

from score_quality import _csv, _object, boolean, integer, text

TIMES = ('release_ms', 'file_ready_ms', 'result_ms', 'asr_start_ms', 'asr_end_ms',
         'mt_start_ms', 'mt_end_ms', 'tts_start_ms', 'tts_ready_ms', 'playback_requested_ms',
         'first_audio_ms', 'audio_end_ms')
STATUS = {'completed', 'error', 'timeout', 'cancelled', 'no_speech', 'ask_repeat', 'awaiting_confirm'}
METRICS = ('file_ready_to_result_ms', 'release_to_result_ms', 'release_to_first_audio_ms',
           'asr_ms', 'mt_ms', 'tts_ms')
TURN_COLUMNS = {'run_id','release_id','device_id','turn_id','sample_id','source_lang','target_lang',
                'input_kind','pid','process_start_id','clock_id','clock_unit','run_phase','attempt_kind',
                'memory_state','terminal_status','first_audio_method','actual_audio_emitted', *TIMES}
MEMORY_COLUMNS = {'run_id','device_id','pid','process_start_id','clock_id','monotonic_ms',
                  'total_pss_kib','native_pss_kib','java_pss_kib','sample_interval_ms','phase',
                  'completed_turns','tool','tool_version','raw_log_path'}


def number(value, label, optional=False):
    if optional and (value is None or value == ''): return None
    if type(value) is bool or not isinstance(value, (int, float, str)):
        raise ValueError(f'{label}: finite nonnegative number required')
    try: result = float(value)
    except (ValueError, OverflowError): raise ValueError(f'{label}: invalid number') from None
    if not math.isfinite(result) or result < 0: raise ValueError(f'{label}: finite nonnegative number required')
    return result


def stats(values):
    values = sorted(values)
    if not values: return dict(n=0, p50=None, p95=None, max=None)
    return dict(n=len(values), p50=values[math.ceil(.5*len(values))-1],
                p95=values[math.ceil(.95*len(values))-1], max=values[-1])


def evaluate(session, manifest, turns, memory, budget):
    if not isinstance(session, dict) or not isinstance(budget, dict): raise ValueError('session/budget must be objects')
    for key in ('run_id','release_id','device_id','scope','clock_id'): text(session.get(key), key)
    if session.get('clock_kind') != 'monotonic' or session.get('clock_unit') != 'ms':
        raise ValueError('session requires monotonic clock in ms')
    kind = session.get('input_kind')
    if kind not in ('file', 'live_mic', 'synthetic'): raise ValueError('unsupported session input kind')
    for key in ('dataset_sha256','model_manifest_sha256','config_sha256'):
        if not isinstance(session.get(key),str) or not re.fullmatch('[0-9a-f]{64}',session[key]):
            raise ValueError(f'invalid session {key}')
    if type(budget.get('schema_version')) is not int or budget['schema_version'] != 1: raise ValueError('invalid budget schema')
    if not isinstance(manifest,list) or not manifest: raise ValueError('nonempty manifest required')
    samples = {}
    for row in manifest:
        if not isinstance(row,dict): raise ValueError('manifest row must be object')
        sid = text(row.get('id'),'sample id')
        if sid in samples: raise ValueError('duplicate manifest sample')
        if (row.get('source_lang'),row.get('target_lang')) not in [('vi','en'),('en','vi')]:
            raise ValueError('manifest requires VI/EN direction')
        samples[sid] = row
    planned = integer(session.get('planned_turn_count'),1,10**9,'planned turns')
    warmup_planned = integer(session.get('warmup_turn_count'),0,10**9,'warmup turns')
    if planned != len(samples): raise ValueError('planned count differs from manifest')
    if not isinstance(turns,list) or not isinstance(memory,list): raise ValueError('turns/memory must be lists')

    issues, measured, seen, process_keys = [], {}, set(), set()
    groups = defaultdict(lambda: defaultdict(list))
    all_values = defaultdict(list)
    warmups = 0
    warmup_status_counts = Counter()
    for row in turns:
        if not isinstance(row,dict): raise ValueError('turn must be object')
        tid, sid = text(row.get('turn_id'),'turn id'), text(row.get('sample_id'),'sample id')
        if tid in seen: raise ValueError('duplicate turn id')
        seen.add(tid)
        if sid not in samples: raise ValueError('turn sample absent from manifest')
        for key in ('run_id','device_id','release_id','clock_id','clock_unit','input_kind'):
            if row.get(key) != session[key]: raise ValueError(f'{tid}: mismatched {key}')
        if any(row.get(k) != samples[sid][k] for k in ('source_lang','target_lang')):
            raise ValueError(f'{tid}: direction mismatch')
        if row.get('attempt_kind') != 'primary': raise ValueError('nonprimary event in performance turns')
        phase = row.get('run_phase')
        if phase not in ('warmup','measured'): raise ValueError('invalid run phase')
        status = text(row.get('terminal_status'),f'{tid}: terminal status')
        if status not in STATUS: raise ValueError('unknown terminal status')
        state = text(row.get('memory_state'),f'{tid}: memory state')
        if state not in ('hit','miss','disabled','unknown'): raise ValueError('unknown memory state')
        pid = integer(row.get('pid'),1,10**9,'PID')
        start = text(row.get('process_start_id'),'process start id')
        process_keys.add((pid,start))
        times = {key:number(row.get(key),f'{tid}: {key}',optional=True) for key in TIMES}
        emitted = boolean(row.get('actual_audio_emitted'),f'{tid}: emitted')
        method = row.get('first_audio_method')
        if method not in (None,'','measured','estimated','enqueue_proxy','not_measured'):
            raise ValueError('unknown first-audio method')
        local_issues = []
        pairs = [('asr_start_ms','asr_end_ms'),('mt_start_ms','mt_end_ms'),('tts_start_ms','tts_ready_ms'),
                 ('result_ms','playback_requested_ms'),('tts_ready_ms','playback_requested_ms'),
                 ('playback_requested_ms','first_audio_ms'),('first_audio_ms','audio_end_ms')]
        boundary = 'file_ready_ms' if kind == 'file' else 'release_ms'
        ordered = [key for key in (boundary,'result_ms','playback_requested_ms','first_audio_ms','audio_end_ms')
                   if times[key] is not None]
        pairs.extend(zip(ordered,ordered[1:]))
        for before,after in pairs:
            if times[before] is not None and times[after] is not None and times[after]<times[before]:
                local_issues.append(f'{tid}: {after} precedes {before}')
        if phase == 'warmup':
            warmups += 1
            warmup_status_counts[status] += 1
            issues.extend(local_issues)
            continue
        if sid in measured: raise ValueError('duplicate measured primary sample')
        measured[sid] = row
        direction = row['source_lang']+'-'+row['target_lang']
        values = {key:[] for key in METRICS}
        if status == 'completed':
            if times[boundary] is None or times['result_ms'] is None:
                local_issues.append(f'{tid}: result boundary not measured')
            for stem,end in [('asr','asr_end_ms'),('mt','mt_end_ms'),('tts','tts_ready_ms')]:
                begin = stem+'_start_ms'
                if times[begin] is None or times[end] is None:
                    partial = (times[begin] is None) != (times[end] is None)
                    if partial or not row.get(stem+'_skip_reason'):
                        local_issues.append(f'{tid}: {stem} missing measurement/skip reason')
                else: values[stem+'_ms'].append(times[end]-times[begin])
            if kind != 'file' and not (method=='measured' and emitted and times['first_audio_ms'] is not None):
                local_issues.append(f'{tid}: measured first audio unavailable')
            if not local_issues:
                values['file_ready_to_result_ms' if kind=='file' else 'release_to_result_ms'].append(times['result_ms']-times[boundary])
                if kind!='file' and method=='measured' and emitted:
                    values['release_to_first_audio_ms'].append(times['first_audio_ms']-times['release_ms'])
        issues.extend(local_issues)
        if local_issues: values = {key:[] for key in METRICS}
        groups[direction][state].append((status,values))
        for key,items in values.items(): all_values[key].extend(items)
    if warmups != warmup_planned: issues.append('warmup count differs from session')
    if session.get('actual_turn_count') not in (None,''):
        if integer(session['actual_turn_count'],0,10**9,'actual turns') != len(measured):
            issues.append('actual turn count differs from logs')

    segments = defaultdict(list)
    for row in memory:
        if not isinstance(row,dict): raise ValueError('memory row must be object')
        for key in ('run_id','device_id','clock_id'):
            if row.get(key) != session[key]: raise ValueError(f'memory mismatched {key}')
        pid = integer(row.get('pid'),1,10**9,'memory PID')
        start = text(row.get('process_start_id'),'memory process start id')
        if (pid,start) not in process_keys: raise ValueError('memory PID/start not observed in turn logs')
        timestamp = number(row.get('monotonic_ms'),'memory timestamp')
        pss = integer(row.get('total_pss_kib'),0,10**12,'total PSS')
        native = integer(row.get('native_pss_kib'),0,10**12,'native PSS',optional=True)
        java = integer(row.get('java_pss_kib'),0,10**12,'Java PSS',optional=True)
        interval = number(row.get('sample_interval_ms'),'memory interval')
        if interval == 0: raise ValueError('memory interval must be positive')
        for key in ('phase','raw_log_path','tool','tool_version'): text(row.get(key),key)
        integer(row.get('completed_turns'),0,10**9,'completed turn count')
        prior = segments[(pid,start)]
        if prior and timestamp <= prior[-1]['timestamp']: raise ValueError('memory times must increase per PID/start')
        prior.append(dict(timestamp=timestamp,pss=pss,native=native,java=java,phase=row['phase'],interval=interval))
    memory_report = dict(samples=len(memory),sampled_peak_pss_mib=max((r['pss']/1024 for rows in segments.values() for r in rows),default=None),
                         phases=sorted({r['phase'] for rows in segments.values() for r in rows}),segments=[])
    for source,label in [('native','sampled_native_peak_mib'),('java','sampled_java_peak_mib')]:
        memory_report[label] = max((r[source]/1024 for rows in segments.values() for r in rows if r[source] is not None),default=None)
    for (pid,start),rows in sorted(segments.items()):
        memory_report['segments'].append(dict(pid=pid,process_start_id=start,samples=len(rows),
            sampled_peak_pss_mib=max(r['pss'] for r in rows)/1024,
            max_observed_gap_ms=max((b['timestamp']-a['timestamp'] for a,b in zip(rows,rows[1:])),default=None),
            declared_intervals_ms=sorted({r['interval'] for r in rows})))
    missing = sorted(set(samples)-set(measured))
    status_counts = Counter(row['terminal_status'] for row in measured.values())
    failed = len(measured)-status_counts['completed']
    grouped = {direction:{state:{**{key:stats([v for _,values in rows for v in values[key]]) for key in METRICS},
                                'status_counts':dict(Counter(status for status,_ in rows)), 'present':len(rows)}
                          for state,rows in sorted(states.items())} for direction,states in sorted(groups.items())}
    report = dict(run_id=session['run_id'],scope=session['scope'],runtime_verified=False,
        data_complete=not missing and not issues and bool(memory),issues=issues,missing_sample_ids=missing,
        measured_turns=len(measured),warmup_turns=warmups,warmup_status_counts=dict(warmup_status_counts),status_counts=dict(status_counts),
        failed_turn_rate=failed/len(measured) if measured else None,
        cold_start_ms=number(session.get('cold_start_ms'),'cold start',optional=True),
        latency={key:stats(all_values[key]) for key in METRICS},by_direction_memory=grouped,memory=memory_report,
        percentile_method='nearest-rank ceil(p*n), one-based',budget_status=budget.get('status'),
        budget_passed=None,budget_issues=[],
        note='Declared logs only. Sampled PSS and measured/proxy audio differ. No quality/safety/provider/runtime certification.')
    if budget.get('status') == 'pilot_required': return report
    reasons = report['budget_issues']
    if budget.get('status') != 'frozen': reasons.append('budget not frozen')
    if not report['data_complete']: reasons.append('data incomplete')
    if any(status!='completed' and count for status,count in warmup_status_counts.items()):
        reasons.append('warmup did not complete successfully')
    if session['scope'] not in ('qdc_file','physical_live_offline'): reasons.append('budget requires real-device scope')
    if not isinstance(budget.get('pilot_run_id'),str) or not budget['pilot_run_id'].strip() or budget['pilot_run_id']==session['run_id']:
        reasons.append('separate pilot required')
    for key in ('scope','device_id','release_id','dataset_sha256','model_manifest_sha256','config_sha256','input_kind'):
        if budget.get(key) != session.get(key): reasons.append(f'budget binding mismatch: {key}')
    try:
        minimum = integer(budget.get('min_measured_turns'),1,10**9,'budget minimum turns')
        warm_min = integer(budget.get('min_warmup_turns'),0,10**9,'budget warmup')
        group_min = integer(budget.get('min_samples_per_group'),1,10**9,'budget group minimum')
        limit = number(budget.get('p95_ms_max'),'budget latency')
        peak_limit = number(budget.get('sampled_peak_pss_mib_max'),'budget PSS')
        failure_limit = number(budget.get('failed_turn_rate_max'),'budget failed rate')
        if failure_limit>1: raise ValueError('failed rate must be fraction0..1')
        metric = budget.get('latency_metric')
        if metric not in ('file_ready_to_result_ms','release_to_first_audio_ms'): raise ValueError('invalid budget boundary')
        if metric != ('file_ready_to_result_ms' if kind=='file' else 'release_to_first_audio_ms'):
            reasons.append('budget latency boundary differs from input kind')
        if len(measured)<minimum or warmups<warm_min: reasons.append('insufficient measured/warmup turns')
        if report['failed_turn_rate'] is None or report['failed_turn_rate']>failure_limit: reasons.append('failed rate exceeds budget')
        if memory_report['sampled_peak_pss_mib'] is None or memory_report['sampled_peak_pss_mib']>peak_limit:
            reasons.append('sampled peak missing/exceeds budget')
        phases = budget.get('required_memory_phases')
        if not isinstance(phases,list) or not phases or not all(isinstance(p,str) and p.strip() for p in phases):
            raise ValueError('budget memory phases required')
        if not set(phases)<=set(memory_report['phases']): reasons.append('memory phase coverage missing')
        if not grouped: reasons.append('no measured latency groups')
        for direction,states in grouped.items():
            for state,values in states.items():
                if state != budget.get('memory_state'): reasons.append(f'{direction}: memory state differs from budget')
                if values[metric]['n']<group_min or values[metric]['p95'] is None or values[metric]['p95']>limit:
                    reasons.append(f'{direction}/{state}: latency missing/insufficient/exceeds budget')
    except ValueError as error: reasons.append(str(error))
    report['budget_passed'] = not reasons
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('session','manifest','turns','memory'): parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--budget',type=Path,default=Path(__file__).resolve().parents[2]/'config/phase3/performance-budget.json')
    parser.add_argument('--output',type=Path)
    args = parser.parse_args(argv)
    try:
        paths = [args.session,args.manifest,args.turns,args.memory,args.budget]
        if args.output:
            for source in paths:
                if args.output.resolve()==source.resolve() or (args.output.exists() and args.output.samefile(source)):
                    raise ValueError('output must not overwrite input or alias')
        session = json.loads(args.session.read_text(),object_pairs_hook=_object)
        manifest = [json.loads(line,object_pairs_hook=_object) for line in args.manifest.read_text().splitlines() if line.strip()]
        hashes = {str(path):hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}
        if not isinstance(session,dict) or session.get('dataset_sha256') != hashes[str(args.manifest)]:
            raise ValueError('session dataset hash differs from actual manifest file')
        report = evaluate(session,manifest,_csv(args.turns,TURN_COLUMNS),_csv(args.memory,MEMORY_COLUMNS),
                          json.loads(args.budget.read_text(),object_pairs_hook=_object))
        report['input_sha256'] = hashes
        payload = json.dumps(report,ensure_ascii=False,indent=2)+'\n'
        if args.output: args.output.write_text(payload,encoding='utf-8')
    except (ValueError,OSError) as error: parser.error(str(error))
    print(payload,end='')
    return 0 if report['data_complete'] and report['budget_passed'] is True else 1


if __name__=='__main__': raise SystemExit(main())
