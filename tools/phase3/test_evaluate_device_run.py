import contextlib
import copy
import csv
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from evaluate_device_run import evaluate, main


class DeviceEvaluationTest(unittest.TestCase):
    def setUp(self):
        self.session = dict(run_id='run', release_id='release', device_id='device', scope='qdc_file',
                            dataset_sha256='a'*64, model_manifest_sha256='b'*64, config_sha256='c'*64,
                            clock_id='boot-monotonic', clock_kind='monotonic', clock_unit='ms',
                            input_kind='file', planned_turn_count=2, warmup_turn_count=1, cold_start_ms=500)
        self.manifest = [dict(id='a', source_lang='vi', target_lang='en'),
                         dict(id='b', source_lang='en', target_lang='vi')]
        self.turns = [self.turn('a', 'warm', 0, 50, 'warmup'), self.turn('a', 't1', 100, 100),
                      self.turn('b', 't2', 400, 200)]
        self.memory = [self.mem(50, 1024, 'init'), self.mem(250, 2048, 'asr'), self.mem(700, 1536, 'tts')]
        self.budget = dict(schema_version=1, status='pilot_required')

    def turn(self, sid, tid, base, duration, phase='measured'):
        return dict(run_id='run', release_id='release', device_id='device', turn_id=tid, sample_id=sid,
                    source_lang='vi' if sid=='a' else 'en', target_lang='en' if sid=='a' else 'vi',
                    input_kind='file', pid=7, process_start_id='p1', clock_id='boot-monotonic', clock_unit='ms',
                    run_phase=phase, attempt_kind='primary', memory_state='miss', terminal_status='completed',
                    file_ready_ms=base, release_ms=None, result_ms=base+duration,
                    asr_start_ms=base+1, asr_end_ms=base+11, mt_start_ms=base+12, mt_end_ms=base+22,
                    tts_start_ms=base+duration+1, tts_ready_ms=base+duration+21,
                    playback_requested_ms=base+duration+22, first_audio_ms=base+duration+30,
                    first_audio_method='measured', actual_audio_emitted=True, audio_end_ms=base+duration+40)

    def mem(self, timestamp, pss, phase):
        return dict(run_id='run', device_id='device', pid=7, process_start_id='p1', clock_id='boot-monotonic',
                    monotonic_ms=timestamp, total_pss_kib=pss, native_pss_kib=None, java_pss_kib=None,
                    sample_interval_ms=100, phase=phase, completed_turns=0, tool='dumpsys', tool_version='fixture',
                    raw_log_path='fixture-only.txt')

    def freeze(self):
        self.budget = dict(schema_version=1, status='frozen', pilot_run_id='separate-pilot',
                           **{k:self.session[k] for k in ['scope','device_id','release_id','dataset_sha256',
                                                          'model_manifest_sha256','config_sha256','input_kind']},
                           memory_state='miss', latency_metric='file_ready_to_result_ms', p95_ms_max=300,
                           sampled_peak_pss_mib_max=3, failed_turn_rate_max=0, min_measured_turns=2,
                           min_warmup_turns=1, min_samples_per_group=1, required_memory_phases=['asr'])

    def report(self):
        return evaluate(self.session, self.manifest, self.turns, self.memory, self.budget)

    def test_pending_budget_never_passes_despite_complete_data(self):
        result = self.report()
        self.assertTrue(result['data_complete'])
        self.assertIsNone(result['budget_passed'])
        self.assertFalse(result['runtime_verified'])

    def test_latency_percentiles_and_pss_use_explicit_units(self):
        result = self.report()
        self.assertEqual(result['latency']['file_ready_to_result_ms'], dict(n=2, p50=100, p95=200, max=200))
        self.assertEqual(result['memory']['sampled_peak_pss_mib'], 2)
        self.assertEqual(result['warmup_turns'], 1)
        self.assertEqual(result['cold_start_ms'], 500)

    def test_complete_frozen_budget_and_limit_failures(self):
        self.freeze()
        self.assertTrue(self.report()['budget_passed'])
        for key, value in [('p95_ms_max', 150), ('sampled_peak_pss_mib_max', 1), ('min_measured_turns', 3),
                           ('min_warmup_turns', 2), ('min_samples_per_group', 2),
                           ('required_memory_phases', ['model_load'])]:
            original = copy.deepcopy(self.budget)
            self.budget[key] = value
            with self.subTest(key=key): self.assertFalse(self.report()['budget_passed'])
            self.budget = original

    def test_missing_turn_or_memory_is_not_complete(self):
        self.turns.pop()
        self.assertEqual(self.report()['missing_sample_ids'], ['b'])
        self.assertFalse(self.report()['data_complete'])
        self.memory = []
        self.assertIsNone(self.report()['memory']['sampled_peak_pss_mib'])

    def test_failures_keep_denominator_and_do_not_become_zero_latency(self):
        self.turns[-1]['terminal_status'] = 'timeout'
        result = self.report()
        self.assertEqual(result['measured_turns'], 2)
        self.assertEqual(result['failed_turn_rate'], .5)
        self.assertEqual(result['latency']['file_ready_to_result_ms']['n'], 1)
        self.freeze()
        self.assertFalse(self.report()['budget_passed'])

    def test_proxy_is_not_audible_latency_and_never_fills_live_gate(self):
        self.session['input_kind'] = 'live_mic'
        for turn in self.turns:
            turn.update(input_kind='live_mic', release_ms=turn['file_ready_ms'],
                        file_ready_ms=None, first_audio_method='enqueue_proxy')
        result = self.report()
        self.assertEqual(result['latency']['release_to_first_audio_ms']['n'], 0)
        self.assertFalse(result['data_complete'])

    def test_reversed_timestamps_are_issues_and_excluded_from_metrics(self):
        self.turns[1]['asr_end_ms'] = self.turns[1]['asr_start_ms']-1
        result = self.report()
        self.assertFalse(result['data_complete'])
        self.assertEqual(result['latency']['file_ready_to_result_ms']['n'], 1)
        self.assertTrue(any('t1' in issue for issue in result['issues']))

    def test_missing_middle_timestamp_cannot_hide_audio_before_release(self):
        self.session['input_kind']='live_mic'
        for turn in self.turns:
            turn.update(input_kind='live_mic',release_ms=turn['file_ready_ms'],file_ready_ms=None)
        self.turns[1].update(playback_requested_ms=None,first_audio_ms=90)
        result=self.report()
        self.assertFalse(result['data_complete'])
        self.assertEqual(result['latency']['release_to_first_audio_ms']['n'],1)

    def test_failed_warmup_is_reported_and_blocks_frozen_budget(self):
        self.turns[0]['terminal_status']='error'
        self.freeze()
        result=self.report()
        self.assertFalse(result['budget_passed'])
        self.assertEqual(result['warmup_status_counts'],{'error':1})

    def test_native_and_java_pss_are_nullable_and_not_discarded(self):
        self.assertIsNone(self.report()['memory']['sampled_native_peak_mib'])
        self.memory[1].update(native_pss_kib=512,java_pss_kib=256)
        result=self.report()['memory']
        self.assertEqual(result['sampled_native_peak_mib'],.5)
        self.assertEqual(result['sampled_java_peak_mib'],.25)

    def test_wrong_run_clock_direction_and_duplicate_identity_are_rejected(self):
        original = copy.deepcopy(self.turns)
        for key, value in [('run_id','other'), ('clock_id','wall-clock'), ('clock_unit','ns'),
                           ('sample_id','missing'), ('source_lang','en'), ('attempt_kind','replay')]:
            self.turns = copy.deepcopy(original); self.turns[1][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): self.report()
        self.turns = original+[dict(original[1])]
        with self.assertRaises(ValueError): self.report()

    def test_nonfinite_boolean_and_negative_measurements_are_rejected(self):
        original = copy.deepcopy(self.turns)
        for value in (True, float('nan'), float('inf'), -1, 'bad'):
            self.turns = copy.deepcopy(original); self.turns[1]['result_ms'] = value
            with self.subTest(value=value), self.assertRaises(ValueError): self.report()
        self.turns = original
        self.memory[0]['total_pss_kib'] = -1
        with self.assertRaises(ValueError): self.report()

    def test_memory_restarts_are_separate_segments_not_one_trend(self):
        self.turns[-1].update(pid=8, process_start_id='p2')
        self.memory.append(dict(self.mem(800, 1024, 'asr'), pid=8, process_start_id='p2'))
        result = self.report()
        self.assertEqual(len(result['memory']['segments']), 2)
        self.assertEqual(result['memory']['segments'][0]['max_observed_gap_ms'], 450)
        self.memory[-1]['process_start_id'] = 'unknown'
        with self.assertRaises(ValueError): self.report()

    def test_memory_clock_and_nonincreasing_samples_are_rejected(self):
        self.memory[0]['clock_id'] = 'wall'
        with self.assertRaises(ValueError): self.report()
        self.memory[0]['clock_id'] = 'boot-monotonic'
        self.memory[1]['monotonic_ms'] = 50
        with self.assertRaises(ValueError): self.report()

    def test_budget_cannot_move_to_other_device_or_acceptance_pilot(self):
        self.freeze()
        for key, value in [('device_id','other'), ('pilot_run_id','run'), ('memory_state','hit'),
                           ('p95_ms_max',None), ('status','typo')]:
            original = copy.deepcopy(self.budget); self.budget[key] = value
            with self.subTest(key=key): self.assertFalse(self.report()['budget_passed'])
            self.budget = original

    def test_direction_and_memory_groups_are_not_blended(self):
        result = self.report()
        self.assertEqual(result['by_direction_memory']['vi-en']['miss']['file_ready_to_result_ms']['n'], 1)
        self.assertEqual(result['by_direction_memory']['en-vi']['miss']['file_ready_to_result_ms']['p95'], 200)

    def test_cli_pending_frozen_and_input_overwrite_guard(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            paths={name:root/(name+('.csv' if name in ('turns','memory') else '.json'))
                   for name in ('session','manifest','turns','memory','budget')}
            paths['manifest'].write_text(''.join(json.dumps(row)+'\n' for row in self.manifest))
            self.session['dataset_sha256'] = hashlib.sha256(paths['manifest'].read_bytes()).hexdigest()
            paths['session'].write_text(json.dumps(self.session))
            for name, rows in [('turns', self.turns), ('memory', self.memory)]:
                with paths[name].open('w',newline='') as f:
                    writer=csv.DictWriter(f,fieldnames=list(rows[0]));writer.writeheader()
                    writer.writerows([{k:str(v).lower() if type(v) is bool else v for k,v in row.items()} for row in rows])
            args=[part for name,path in paths.items() for part in ('--'+name,str(path))]
            for status, exit_code in [('pending',1),('frozen',0)]:
                if status=='frozen': self.freeze()
                paths['budget'].write_text(json.dumps(self.budget))
                with contextlib.redirect_stdout(io.StringIO()): self.assertEqual(main(args),exit_code)
            before=paths['session'].read_bytes()
            with contextlib.redirect_stderr(io.StringIO()),self.assertRaises(SystemExit) as error:
                main(args+['--output',str(paths['session'])])
            self.assertEqual(error.exception.code,2)
            self.assertEqual(paths['session'].read_bytes(),before)


if __name__=='__main__': unittest.main()
