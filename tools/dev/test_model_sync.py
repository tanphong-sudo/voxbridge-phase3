#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import os
import subprocess
from model_integrity import DigestCache, integrity_text, promote_tree, sha256_file


SPEC = importlib.util.spec_from_file_location("model_sync", Path(__file__).with_name("model_sync.py"))
assert SPEC and SPEC.loader
model_sync = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(model_sync)


class ModelSyncTest(unittest.TestCase):
    def test_device_root_rejects_broad_and_injected_paths(self):
        expected = '/storage/emulated/0/Android/data/com.voxbridge/files/models'
        self.assertEqual(expected, model_sync.validate_device_model_root(expected))
        for path in ('/', '/storage/emulated/0', expected + '/../', expected + ';echo bad'):
            with self.assertRaises(SystemExit):
                model_sync.validate_device_model_root(path)

    def test_remote_failed_second_rename_rolls_back(self):
        calls = []
        def injected(command, **kwargs):
            calls.append(command)
            if command[-3:] == ['mv', '/models/asr.part-tree', '/models/asr']:
                raise subprocess.CalledProcessError(1, command)
            if command[-3:] == ['test', '-e', '/models/asr.previous-tree']:
                return subprocess.CompletedProcess(command, 1)
            return subprocess.CompletedProcess(command, 0)
        with patch.object(model_sync, 'run', side_effect=injected):
            with self.assertRaises(subprocess.CalledProcessError):
                model_sync.promote_remote_tree('adb', '/models/asr.part-tree', '/models/asr')
        self.assertEqual(['adb', 'shell', 'mv', '/models/asr.previous-tree', '/models/asr'], calls[-1])

    def test_recovery_restores_previous_tree_when_live_missing(self):
        responses = [subprocess.CompletedProcess([], 0), subprocess.CompletedProcess([], 1), subprocess.CompletedProcess([], 0)]
        with patch.object(model_sync, 'run', side_effect=responses) as commands:
            model_sync.recover_remote_tree('adb', '/models/asr')
            self.assertEqual(['adb', 'shell', 'mv', '/models/asr.previous-tree', '/models/asr'], commands.call_args.args[0])

    def test_recovery_keeps_both_copies_if_ambiguous(self):
        with patch.object(model_sync, 'run', return_value=subprocess.CompletedProcess([], 0)) as commands:
            with self.assertRaises(SystemExit):
                model_sync.recover_remote_tree('adb', '/models/asr')
            self.assertEqual(2, commands.call_count)
    def test_failed_promotion_restores_old_tree(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            old, stage = root/'models', root/'staging'
            old.mkdir(); stage.mkdir()
            (old/'data').write_text('old'); (stage/'data').write_text('new')
            original = os.replace
            def injected(src, dest):
                if src == stage:
                    raise OSError('simulated full disk / interrupted promotion')
                return original(src, dest)
            with patch('model_integrity.os.replace', side_effect=injected):
                with self.assertRaises(OSError):
                    promote_tree(stage, old)
            self.assertEqual('old', (old/'data').read_text())
            self.assertEqual('new', (stage/'data').read_text())

    def test_digest_cache_opt_in_and_same_size_replacement(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); model = root/'model'
            model.write_bytes(b'abc')
            cache = DigestCache(root/'cache.json', enabled=True)
            first = cache.digest(model); cache.save()
            with patch('model_integrity.sha256_file', side_effect=AssertionError('must hit cache')):
                self.assertEqual(first, DigestCache(root/'cache.json', enabled=True).digest(model))
            model.write_bytes(b'abd')
            self.assertNotEqual(first, cache.digest(model))
            with patch('model_integrity.sha256_file', return_value='fresh') as full:
                self.assertEqual('fresh', DigestCache(root/'cache.json').digest(model))
                full.assert_called_once()

    def test_same_size_corrupted_remote_file_is_not_skipped(self):
        with tempfile.TemporaryDirectory() as tmp:
            local = Path(tmp)/'model'; local.write_bytes(b'abc')
            with patch.object(model_sync, 'remote_size', return_value=3), \
                 patch.object(model_sync, 'remote_digest', side_effect=['0'*64, sha256_file(local)]), \
                 patch.object(model_sync, 'run'), \
                 patch.object(model_sync.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)) as push:
                self.assertEqual(3, model_sync.push_file_atomic('adb', local, '/models/model'))
                push.assert_called_once()

    def test_corrupt_staging_file_does_not_promote(self):
        with tempfile.TemporaryDirectory() as tmp:
            local = Path(tmp)/'model'; local.write_bytes(b'abc')
            with patch.object(model_sync, 'remote_size', return_value=None), \
                 patch.object(model_sync, 'remote_digest', return_value='bad'), \
                 patch.object(model_sync, 'run') as commands, \
                 patch.object(model_sync.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)):
                with self.assertRaises(SystemExit):
                    model_sync.push_file_atomic('adb', local, '/models/model')
                self.assertFalse(any('mv' in call.args[0] for call in commands.call_args_list))

    def test_integrity_contract_supports_spaces(self):
        with tempfile.TemporaryDirectory() as tmp:
            local = Path(tmp)/'model'; local.write_bytes(b'abc')
            self.assertEqual(f'{sha256_file(local)} 3 speech/model name.onnx\n',
                             integrity_text([(local, 'speech/model name.onnx')]))

    def test_tree_verification_checks_bytes_not_marker(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); model = root/'with space.onnx'; model.write_bytes(b'abc')
            good = subprocess.CompletedProcess([], 0, stdout=sha256_file(model)+'  remote\n')
            with patch.object(model_sync, 'run', return_value=good) as command:
                model_sync.verify_remote_tree('adb', root, '/models/tree')
                self.assertIn("'/models/tree/with space.onnx'", command.call_args.args[0][-1])
            bad = subprocess.CompletedProcess([], 0, stdout='0'*64+'  remote\n')
            with patch.object(model_sync, 'run', return_value=bad):
                with self.assertRaises(SystemExit):
                    model_sync.verify_remote_tree('adb', root, '/models/tree')

    def test_successful_promotion_keeps_new_and_removes_only_own_backup(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); old = root/'models'; stage = root/'stage'
            old.mkdir(); stage.mkdir(); (old/'data').write_text('old'); (stage/'data').write_text('new')
            recovery = root/'models.backup-earlier'; recovery.mkdir()
            promote_tree(stage, old)
            self.assertEqual('new', (old/'data').read_text())
            self.assertEqual([recovery], list(root.glob('models.backup-*')))

    def test_selected_files_expands_only_manifest_trees(self):
        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp)
            (cache / "vad").mkdir()
            (cache / "vad/silero_vad.onnx").write_bytes(b"vad")
            (cache / "speech_to_text/whisper-tiny-onnx").mkdir(parents=True)
            (cache / "speech_to_text/whisper-tiny-onnx/model.onnx").write_bytes(b"model")
            (cache / "speech_to_text/unselected").mkdir(parents=True)
            (cache / "speech_to_text/unselected/huge.onnx").write_bytes(b"do-not-copy")
            manifest = {
                "artifacts": [
                    {"path": "vad/silero_vad.onnx", "kind": "file", "expectedBytes": 3},
                    {"path": "speech_to_text/whisper-tiny-onnx", "kind": "tree", "expectedBytes": 5},
                ]
            }
            selected = [rel for _, rel in model_sync.selected_files(cache, manifest)]
            self.assertEqual(
                selected,
                ["speech_to_text/whisper-tiny-onnx/model.onnx", "vad/silero_vad.onnx"],
            )

    def test_expected_size_mismatch_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp)
            (cache / "vad").mkdir()
            (cache / "vad/silero_vad.onnx").write_bytes(b"bad")
            manifest = {
                "artifacts": [
                    {"path": "vad/silero_vad.onnx", "kind": "file", "expectedBytes": 99}
                ]
            }
            with self.assertRaises(SystemExit):
                model_sync.selected_files(cache, manifest)

    def test_required_tree_file_is_enforced(self):
        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp)
            tree = cache / "speech_to_text/whisper-tiny-onnx"
            tree.mkdir(parents=True)
            (tree / "encoder_model.onnx").write_bytes(b"encoder")
            artifact = {
                "path": "speech_to_text/whisper-tiny-onnx",
                "kind": "tree",
                "requiredFiles": ["encoder_model.onnx", "decoder_model.onnx"],
            }
            self.assertIn(
                "speech_to_text/whisper-tiny-onnx/decoder_model.onnx",
                model_sync.artifact_missing(cache, artifact),
            )

    def test_tree_fingerprint_is_content_sensitive_and_ignores_ds_store(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "sub").mkdir()
            payload = root / "sub/model.bin"
            payload.write_bytes(b"abc")
            first = model_sync.tree_fingerprint(root)
            (root / ".DS_Store").write_bytes(b"finder-noise")
            self.assertEqual(first, model_sync.tree_fingerprint(root))
            payload.write_bytes(b"abd")  # same size, different content must invalidate marker
            self.assertNotEqual(first, model_sync.tree_fingerprint(root))


if __name__ == "__main__":
    unittest.main()
