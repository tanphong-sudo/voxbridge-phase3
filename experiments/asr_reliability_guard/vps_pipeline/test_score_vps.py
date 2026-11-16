import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import wave

from score_vps import aggregate, normalize, validate_manifest


class FixtureMetrics:
    """Contract spy only; no synthetic score is emitted by the real scorer CLI."""
    versions={'sacrebleu':'test-only','whisper-normalizer':'test-only'}
    def __init__(self):self.calls=[]
    def english(self,text):return normalize(text)
    def chrf(self,hyps,refs):
        self.calls.append((list(hyps),list(refs)))
        return {'score':None,'signature':'unit-test spy','samples':len(refs),'nonempty_hypotheses':sum(bool(x) for x in hyps)}


def fixtures(root,n=200):
    wav=root/'audio.wav'
    with wave.open(str(wav),'wb') as w:
        w.setparams((1,2,16000,0,'NONE','not compressed'));w.writeframes(b'\x00\x00'*32)
    digest=hashlib.sha256(wav.read_bytes()).hexdigest()
    manifest=[];results=[]
    for i in range(n):
        lang='en' if i%2==0 else 'vi';sid=f'ID-{i}'
        manifest.append(dict(id=sid,source_lang=lang,target_lang='vi' if lang=='en' else 'en',
                             sha256='a'*64,reference_source='hello world',reference_target='xin chao',human_verified=False))
        # Real sample files need unique paths; copies preserve the exact PCM fixture bytes.
        dest=root/(sid+'.wav');dest.write_bytes(wav.read_bytes())
        results.append(dict(id=sid,type='sample',source_lang=lang,target_lang=manifest[-1]['target_lang'],
            input_sha256='a'*64,raw_asr='hello world',raw_mt='xin chao',final_text='xin chao',
            decision='ModelTranslation',failure=None,mt_calls=1,translation_audio_generated=True,
            pipeline_to_audio_ready_ms=100,awaiting_confirmation=False,
            tts=dict(wav=str(dest),sha256=digest,sample_rate=16000,samples=32,cached_prompt=False,ok=True)))
    return manifest,results


class VpsScoreTest(unittest.TestCase):
    def test_full200_coverage_missing_is_empty_and_incomplete_not_filtered(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,r=fixtures(Path(tmp));metric=FixtureMetrics()
            report=aggregate(m,r[:1],metric)
            self.assertEqual(report['coverage'],dict(expected=200,present=1,missing=199))
            self.assertEqual(report['asr']['raw_all_inputs']['all']['samples'],200)
            self.assertEqual(report['asr']['raw_all_inputs']['all']['wer'],199/200)
            self.assertEqual(report['mt']['final_all200']['samples'],200)
            self.assertEqual(report['mt']['final_all200']['nonempty_hypotheses'],1)
            self.assertFalse(report['complete'])

    def test_duplicates_unexpected_ids_and_wrong_manifest_size_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,r=fixtures(Path(tmp))
            for rows in [r+[r[0]],[dict(r[0],id='unknown')]]:
                with self.assertRaises(ValueError):aggregate(m,rows,FixtureMetrics())
            with self.assertRaises(ValueError):validate_manifest(m[:-1])

    def test_draft_never_replaces_final_or_raw_asr_and_rejected_quality_has_own_denominator(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,r=fixtures(Path(tmp));row=r[0]
            row.update(decision='AskRepeat',decision_reason='asr_low_confidence',translation_audio_generated=False,
                final_text=None,tentative_output=dict(source_text='hello world',raw_source_text='hello world',
                translated_text='xin chao',whole_utterance_uncertain=True),tentative_output_enabled=True)
            row['tts']['cached_prompt']=True
            before=copy.deepcopy(r);metric=FixtureMetrics();report=aggregate(m,r,metric)
            self.assertTrue(report['complete'])
            self.assertEqual(report['asr']['raw_all_inputs']['all']['wer'],0)
            self.assertEqual(report['asr']['accepted_path_rejects_empty']['all']['wer'],1/200)
            self.assertEqual(report['mt']['final_all200']['nonempty_hypotheses'],199)
            self.assertEqual(report['mt']['accepted_subset']['samples'],199)
            self.assertEqual(report['drafts']['rejected_samples'],1)
            self.assertEqual(report['drafts']['target']['samples'],1)
            self.assertEqual(report['drafts']['target']['nonempty_hypotheses'],1)
            self.assertEqual(r,before)

    def test_every_tts_hash_header_and_truncated_payload_is_checked(self):
        for bad in ['hash','header','payload']:
            with self.subTest(bad=bad),tempfile.TemporaryDirectory() as tmp:
                m,r=fixtures(Path(tmp));event=r[100]['tts']
                if bad=='hash':event['sha256']='b'*64
                elif bad=='header':event['samples']=99
                else:
                    p=Path(event['wav']);p.write_bytes(p.read_bytes()[:-3]);event['sha256']=hashlib.sha256(p.read_bytes()).hexdigest()
                report=aggregate(m,r,FixtureMetrics())
                self.assertFalse(report['complete'])
                self.assertTrue(any(i['id']=='ID-100' and i['reason'].startswith('tts_audio_') for i in report['issues']))

    def test_no_draft_tts_extra_mt_or_silently_declared_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,r=fixtures(Path(tmp))
            r[0].update(decision='AskRepeat',final_text=None,tentative_output=dict(source_text='hello world',
                raw_source_text='hello world',translated_text='xin chao',whole_utterance_uncertain=True))
            r[1]['mt_calls']=2;r[2]['failure']='declared runtime failure'
            report=aggregate(m,r,FixtureMetrics());reasons={x['reason'] for x in report['issues']}
            self.assertFalse(report['complete'])
            self.assertIn('rejected_translation_audio',reasons)
            self.assertIn('rejected_tts_not_cached_prompt',reasons)
            self.assertIn('extra_mt_call',reasons)
            self.assertIn('declared_failure',reasons)

    def test_complete_run_audits_all200_wavs_and_raw_metric_denominators(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,r=fixtures(Path(tmp));report=aggregate(m,r,FixtureMetrics())
            self.assertTrue(report['complete'])
            self.assertEqual(report['tts']['verified_wavs'],200)
            self.assertEqual(report['asr']['en_normalized_all_inputs']['samples'],100)
            self.assertEqual(report['asr']['raw_all_inputs']['vi']['samples'],100)
            self.assertEqual(report['mt']['final_all200']['samples'],200)
            self.assertFalse(report['human_acceptance'])
            self.assertFalse(report['qnn_latency_claim'])

    def test_cli_writes_all200_csv_and_separate_baseline_without_altering_raw_text(self):
        import contextlib
        import csv
        import io
        from unittest.mock import patch
        import score_vps
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);m,r=fixtures(root)
            r[0]['raw_asr']='hello world ' * 200
            manifest=root/'manifest.jsonl';results=root/'results.jsonl'
            manifest.write_text(''.join(json.dumps(x)+'\n' for x in m))
            results.write_text(''.join(json.dumps(x)+'\n' for x in r))
            with patch.object(score_vps,'Metrics',FixtureMetrics),contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(score_vps.main(['--manifest',str(manifest),'--results',str(results),
                    '--baseline',str(results),'--out-dir',str(root/'score')]),0)
            summary=json.loads((root/'score/summary.json').read_text())
            self.assertEqual(summary['baseline_comparison']['decision_changes'],[])
            with (root/'score/sample-results.csv').open(encoding='utf-8-sig') as f:
                rows=list(csv.DictReader(f))
            self.assertEqual(len(rows),200)
            self.assertEqual(rows[0]['raw_asr'],r[0]['raw_asr'])
            self.assertIn('Baseline', (root/'score/report.md').read_text())

    def test_best_effort_display_adds_only_ask_draft_and_retains_missing_and_no_speech(self):
        class PairingMetric(FixtureMetrics):
            # Deliberate exact-match contract oracle, not a substitute chrF implementation.
            def chrf(self,hyps,refs):
                result=super().chrf(hyps,refs)
                result['score']=100*sum(h==r for h,r in zip(hyps,refs))/len(refs) if refs else None
                return result
        with tempfile.TemporaryDirectory() as tmp:
            m,r=fixtures(Path(tmp));r=r[:-1]  # Missing ID-199 remains empty in all200.
            r[0].update(decision='AskRepeat',final_text=None,translation_audio_generated=False)
            r[0]['tts']['cached_prompt']=True
            r[1].update(decision='NoSpeech',final_text=None,raw_asr=None,translation_audio_generated=False)
            r[1]['tts']['cached_prompt']=True
            initial=aggregate(m,r,PairingMetric())
            r[0]['tentative_output']=dict(source_text='hello world',raw_source_text='hello world',
                translated_text='xin chao',whole_utterance_uncertain=True)
            after=aggregate(m,r,PairingMetric())
            before_display=initial['mt']['best_effort_display_MT']
            display=after['mt']['best_effort_display_MT']
            self.assertEqual(display['samples'],200)
            self.assertEqual(display['nonempty_hypotheses'],198)
            self.assertEqual(display['coverage'],198/200)
            self.assertEqual(display['accepted_final_samples'],197)
            self.assertEqual(display['untrusted_draft_samples'],1)
            self.assertGreater(display['score'],before_display['score'])
            self.assertEqual(initial['mt']['final_all200'],after['mt']['final_all200'])
            self.assertEqual(initial['decisions'],after['decisions'])
            self.assertEqual(initial['accepted_translation_samples'],after['accepted_translation_samples'])
            self.assertEqual(after['coverage']['missing'],1)
            self.assertFalse(display['grants_acceptance_or_speech'])

if __name__=='__main__':unittest.main()
