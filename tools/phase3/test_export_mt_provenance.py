"""Test export orchestration with external ML/Hub boundaries stubbed; never load/download weights."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, patch


SCRIPT=Path(__file__).resolve().parents[2]/'model-tools-python/scripts/export_opus_mt.py'


class ExportProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.dest=Path(self.temp.name)/'models'
        self.repo='Helsinki-NLP/opus-mt-vi-en';self.sha='a'*40
        self.hub=Mock();self.hub.model_info.return_value.sha=self.sha
        self.ort=Mock();self.tokenizer=Mock();self.quantizer=Mock();self.qconfig=Mock()
        def save(directory):
            directory.mkdir(parents=True,exist_ok=True)
            for stem in ('encoder_model','decoder_model','decoder_with_past_model'):
                (directory/(stem+'.onnx')).write_bytes(b'not a real model')
        self.ort.from_pretrained.return_value.save_pretrained.side_effect=save
        def quantize(directory,file_name):
            q=Mock()
            q.quantize.side_effect=lambda save_dir,quantization_config:(save_dir/file_name.replace('.onnx','_quantized.onnx')).write_bytes(b'not a real quantized model')
            return q
        self.quantizer.from_pretrained.side_effect=quantize
        paths=types.ModuleType('paths');paths.ensure=lambda p:p.mkdir(parents=True,exist_ok=True)
        for name in ('VI_EN','EN_VI','EN_ZH','ZH_VI','EN_KO','KO_EN'):
            setattr(paths,'HF_OPUS_'+name,self.repo)
            setattr(paths,'MT_'+name,self.dest)
        modules={name:types.ModuleType(name) for name in ['onnx','optimum','optimum.onnxruntime',
                  'optimum.onnxruntime.configuration','transformers','huggingface_hub']}
        modules['paths']=paths
        self.check_model = Mock()
        modules['onnx'].checker = types.SimpleNamespace(check_model=self.check_model)
        modules['optimum.onnxruntime'].ORTModelForSeq2SeqLM=self.ort
        modules['optimum.onnxruntime'].ORTQuantizer=self.quantizer
        modules['optimum.onnxruntime.configuration'].AutoQuantizationConfig=self.qconfig
        modules['transformers'].AutoTokenizer=self.tokenizer
        modules['huggingface_hub'].HfApi=lambda:self.hub
        spec=importlib.util.spec_from_file_location('phase3_export_mt_under_test',SCRIPT)
        self.module=importlib.util.module_from_spec(spec)
        with patch.dict(sys.modules,modules): spec.loader.exec_module(self.module)
        self.module._io_names=lambda p:dict(inputs=['fixture_input'],outputs=['fixture_output'])

    def export(self,quantize=True,**kwargs):
        with contextlib.redirect_stdout(io.StringIO()): self.module.export_one('vi-en',quantize,**kwargs)

    def test_model_and_tokenizer_share_one_immutable_commit(self):
        self.export()
        self.hub.model_info.assert_called_once_with(self.repo)
        self.assertEqual(self.ort.from_pretrained.call_args.kwargs['revision'],self.sha)
        self.assertEqual(self.tokenizer.from_pretrained.call_args.kwargs['revision'],self.sha)
        record=json.loads((self.dest/'source.json').read_text())
        self.assertEqual(record['model'],self.repo)
        self.assertEqual(record['revision'],self.sha)
        self.assertEqual(record['precision'],'float32+dynamic-int8')
        self.assertEqual(len(record['selected_graphs']),3)
        self.assertEqual(self.check_model.call_count,3)
        self.assertTrue(all(name.endswith('_quantized.onnx') for name in record['selected_graphs']))
        self.assertTrue(all((self.dest/name).is_file() for name in record['selected_graphs']))

    def test_explicit_commit_is_resolved_and_used_consistently(self):
        self.export(revision=self.sha)
        self.hub.model_info.assert_called_once_with(self.repo,revision=self.sha)
        self.assertEqual(self.ort.from_pretrained.call_args.kwargs['revision'],self.sha)

    def test_invalid_hub_commit_preserves_existing_tree(self):
        self.dest.mkdir();sentinel=self.dest/'old-fixture.onnx';sentinel.write_bytes(b'prior cache')
        for revision in (None,'main','a'*39,'G'*40):
            self.hub.model_info.return_value.sha=revision
            with self.subTest(revision=revision):
                with self.assertRaises(ValueError): self.export()
                self.assertEqual(sentinel.read_bytes(),b'prior cache')
        self.ort.from_pretrained.assert_not_called()

    def test_hub_failure_occurs_before_touching_existing_tree(self):
        self.dest.mkdir();sentinel=self.dest/'old-fixture.onnx';sentinel.write_bytes(b'prior cache')
        self.hub.model_info.side_effect=RuntimeError('fixture network unavailable')
        with self.assertRaises(RuntimeError): self.export()
        self.assertEqual(sentinel.read_bytes(),b'prior cache')

    def test_fp32_mode_is_recorded_without_quantization_claim(self):
        self.export(quantize=False)
        record=json.loads((self.dest/'source.json').read_text())
        self.assertEqual(record['precision'],'float32')
        self.assertIsNone(record['quantization'])
        self.assertTrue(all(not name.endswith('_quantized.onnx') for name in record['selected_graphs']))
        self.quantizer.from_pretrained.assert_not_called()

    def test_cli_commit_requires_one_direction(self):
        with patch.object(sys,'argv',['export_opus_mt.py','--revision',self.sha]),contextlib.redirect_stderr(io.StringIO()),self.assertRaises(SystemExit) as error:
            self.module.main()
        self.assertEqual(error.exception.code,2)
        self.ort.from_pretrained.assert_not_called()

    def test_failed_export_does_not_publish_success_provenance(self):
        self.ort.from_pretrained.return_value.save_pretrained.side_effect=RuntimeError('fixture export failure')
        with self.assertRaises(RuntimeError): self.export()
        self.assertFalse((self.dest/'source.json').exists())

    def test_checker_failure_keeps_previous_export(self):
        self.dest.mkdir(); sentinel=self.dest/'prior.txt'; sentinel.write_bytes(b'preserve')
        self.check_model.side_effect=RuntimeError('fixture invalid ONNX')
        with self.assertRaises(RuntimeError): self.export()
        self.assertEqual(sentinel.read_bytes(),b'preserve')
        self.assertFalse((self.dest/'source.json').exists())

    def test_conversion_failure_keeps_previous_export(self):
        self.dest.mkdir(); sentinel=self.dest/'prior.txt'; sentinel.write_bytes(b'preserve')
        self.ort.from_pretrained.return_value.save_pretrained.side_effect=RuntimeError('fixture conversion failed')
        with self.assertRaises(RuntimeError): self.export()
        self.assertEqual(sentinel.read_bytes(),b'preserve')

    def test_partial_graph_export_cannot_publish_provenance(self):
        self.ort.from_pretrained.return_value.save_pretrained.side_effect=lambda directory:(directory/'encoder_model.onnx').write_bytes(b'fixture')
        with self.assertRaises(ValueError): self.export(quantize=False)
        self.assertFalse((self.dest/'source.json').exists())

    def test_missing_quantized_outputs_cannot_be_labeled_int8(self):
        self.quantizer.from_pretrained.side_effect=lambda *args,**kwargs:Mock()
        with self.assertRaises(ValueError): self.export()
        self.assertFalse((self.dest/'source.json').exists())


if __name__=='__main__':unittest.main()
