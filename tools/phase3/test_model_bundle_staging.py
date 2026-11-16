import contextlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch


SCRIPT=Path(__file__).resolve().parents[2]/'tools/dev/model_bundle.py'
SPEC=importlib.util.spec_from_file_location('phase3_model_bundle_under_test',SCRIPT)
bundle=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(bundle)


class StagingTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.src=self.root/'cache';self.dst=self.root/'staged'
        for relative in bundle.required_files(): self.write(relative,b'fixture')
        for directory,model in bundle.ASR_MODELS.items():
            self.write(directory+'/source.json',json.dumps(dict(model=model,revision='a'*40,precision='float32',
                       storage='lossless shared ONNX external initializers')).encode())
            self.write(directory+'/vocab.json',b'{}');self.write(directory+'/shared_weights.bin',b'fixture')
        for directory in bundle.VOICE_DIRS:
            self.write(directory+'/voice.onnx',b'fixture')
            self.write(directory+'/voice.onnx.json',b'{}')
            self.write(directory+'/espeak-ng-data/phontab',b'fixture')

    def write(self,relative,data):
        p=self.src/relative;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)

    def stage(self,**kwargs):
        with contextlib.redirect_stdout(io.StringIO()):bundle.bundle(self.src,self.dst,**kwargs)

    def test_runtime_files_present_but_extra_graphs_archives_and_hidden_files_excluded(self):
        extras=['speech_to_text/phowhisper-base-onnx/backup.onnx','translation/opus-mt-vi-en/encoder_model.onnx',
                'translation/opus-mt-vi-en/unselected.onnx','translation/opus-mt-vi-en/.env',
                'translation/opus-mt-vi-en/debug.zip','translation/opus-mt-en-ko/model.onnx',
                'translation/opus-mt-vi-en/LICENSE.zip','NOTICE.onnx',
                'text_to_speech/piper/vits-piper-en_US-lessac-medium/backup.onnx.json',
                'text_to_speech/cache/extra_prompt.pcm']
        for relative in extras:self.write(relative,b'not selected')
        self.stage()
        for relative in extras:
            with self.subTest(relative=relative):self.assertFalse((self.dst/relative).exists())
        self.assertTrue((self.dst/'speech_to_text/whisper-small-onnx/shared_weights.bin').is_file())
        self.assertTrue((self.dst/'translation/opus-mt-vi-en/decoder_with_past_model_quantized.onnx').is_file())
        self.assertTrue((self.dst/'text_to_speech/piper/vits-piper-en_US-lessac-medium/voice.onnx.json').is_file())
        self.assertEqual((self.dst/'text_to_speech/cache/index.txt').read_text().splitlines(),
                         ['ask_repeat_vi','ask_repeat_en','no_speech_vi','no_speech_en'])

    def test_optional_commands_stage_only_complete_pairs_and_are_hashed(self):
        self.write('text_to_speech/cache/stop_machine_vi.pcm', b'\x01\x00')
        self.write('text_to_speech/cache/stop_machine_vi.sr', b'22050')
        self.write('text_to_speech/cache/evacuate_en.pcm', b'\x01\x00')
        self.stage()
        self.assertEqual((self.dst/'text_to_speech/cache/stop_machine_vi.pcm').read_bytes(), b'\x01\x00')
        self.assertIn('stop_machine_vi', (self.dst/'text_to_speech/cache/index.txt').read_text().splitlines())
        self.assertIn('text_to_speech/cache/stop_machine_vi.pcm', (self.dst/'integrity.sha256').read_text())
        self.assertFalse((self.dst/'text_to_speech/cache/evacuate_en.pcm').exists())

    def test_staged_payload_integrity_matches_selected_bytes(self):
        import hashlib
        self.stage()
        rows=(self.dst/'integrity.sha256').read_text().splitlines()
        records={}
        for row in rows:
            digest,size,name=row.split(' ',2)
            payload=(self.dst/name).read_bytes()
            self.assertEqual(digest,hashlib.sha256(payload).hexdigest())
            self.assertEqual(int(size),len(payload))
            records[name]=digest
        self.assertIn('translation/opus-mt-vi-en/encoder_model_quantized.onnx',records)
        self.assertIn('profile.txt',records)
        self.assertNotIn('integrity.sha256',records)
        self.assertNotIn('version.txt',records)

    def test_notices_and_provenance_are_retained_in_every_selected_component(self):
        notices=['NOTICE.md','LICENSES/Apache-2.0.txt','vad/LICENSE','text_to_speech/cache/NOTICE.txt',
                 'speech_to_text/phowhisper-base-onnx/LICENSE.md',
                 'translation/opus-mt-vi-en/README.md','translation/opus-mt-vi-en/source.json',
                 'text_to_speech/piper/vits-piper-en_US-lessac-medium/MODEL_CARD.md']
        for relative in notices:self.write(relative,b'fixture notice, not legal clearance')
        self.stage()
        for relative in notices:
            with self.subTest(relative=relative):self.assertEqual((self.dst/relative).read_bytes(),b'fixture notice, not legal clearance')

    def test_ambiguous_or_empty_voice_and_empty_espeak_are_rejected(self):
        voice=self.src/'text_to_speech/piper/vits-piper-en_US-lessac-medium'
        (voice/'extra.onnx').write_bytes(b'extra')
        with self.assertRaises(ValueError):bundle.validate(self.src)
        (voice/'extra.onnx').unlink();(voice/'voice.onnx').write_bytes(b'')
        with self.assertRaises(ValueError):bundle.validate(self.src)
        (voice/'voice.onnx').write_bytes(b'fixture');(voice/'espeak-ng-data/phontab').unlink()
        with self.assertRaises(ValueError):bundle.validate(self.src)

    def test_prepare_asr_preserves_destination_asr_when_source_is_absent(self):
        self.stage()
        for directory in bundle.ASR_DIRS:shutil.rmtree(self.src/directory)
        self.stage(include_asr=False)
        self.assertEqual((self.dst/'speech_to_text/whisper-small-onnx/shared_weights.bin').read_bytes(),b'fixture')

    def test_copy_failure_does_not_remove_prior_destination(self):
        self.dst.mkdir();prior=self.dst/'prior.txt';prior.write_bytes(b'preserve')
        with patch.object(bundle.shutil,'copy2',side_effect=OSError('fixture copy failed')):
            with self.assertRaises(OSError):self.stage()
        self.assertEqual(prior.read_bytes(),b'preserve')

    def test_publish_failure_restores_prior_destination(self):
        self.dst.mkdir();prior=self.dst/'prior.txt';prior.write_bytes(b'preserve')
        with patch.object(bundle.shutil,'move',side_effect=OSError('fixture publish failed')):
            with self.assertRaises(OSError):self.stage()
        self.assertEqual(prior.read_bytes(),b'preserve')

    def test_failed_restore_keeps_backup_outside_staging_cleanup(self):
        self.dst.mkdir();(self.dst/'prior.txt').write_bytes(b'preserve')
        original=Path.rename
        def fail_restore(path,target):
            if Path(target).resolve()==self.dst.resolve() and path.parent.name.startswith('.vi-en-previous-'):
                raise OSError('fixture restore failed')
            return original(path,target)
        with patch.object(bundle.shutil,'move',side_effect=OSError('fixture publish failed')),patch.object(Path,'rename',fail_restore):
            with self.assertRaises(OSError):self.stage()
        backups=list(self.root.glob('.vi-en-previous-*/models/prior.txt'))
        self.assertEqual(len(backups),1)
        self.assertEqual(backups[0].read_bytes(),b'preserve')

    def test_selected_symlink_outside_cache_is_rejected_before_publish(self):
        external=self.root/'external.onnx';external.write_bytes(b'do not copy')
        selected=self.src/'translation/opus-mt-vi-en/encoder_model_quantized.onnx'
        selected.unlink();selected.symlink_to(external)
        with self.assertRaises(ValueError):self.stage()
        self.assertFalse(self.dst.exists())

    def test_overlapping_source_and_destination_are_rejected(self):
        with self.assertRaises(ValueError):bundle.bundle(self.src,self.src/'nested')
        with self.assertRaises(ValueError):bundle.bundle(self.src,self.src)

    def test_existing_destination_file_is_not_replaced(self):
        self.dst.write_bytes(b'not a directory; preserve')
        with self.assertRaises(ValueError):self.stage()
        self.assertEqual(self.dst.read_bytes(),b'not a directory; preserve')

    def test_asr_provenance_requires_object_and_nonempty_shared_weights(self):
        directory=self.src/'speech_to_text/whisper-small-onnx'
        source=directory/'source.json';original=source.read_bytes();source.write_text('[]')
        with self.assertRaises(ValueError):bundle.validate(self.src)
        source.write_bytes(original);(directory/'shared_weights.bin').write_bytes(b'')
        with self.assertRaises(ValueError):bundle.validate(self.src)


if __name__=='__main__':unittest.main()
