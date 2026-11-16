"""Real ONNX/ORT regression: storage deduplication must preserve inference exactly."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import numpy as np
import onnx
from onnx import helper, numpy_helper, TensorProto
import onnxruntime as ort

ROOT = Path(__file__).resolve().parents[1]

class SharedWeightsTests(unittest.TestCase):
    def test_exact_values_and_shared_storage(self):
        spec = importlib.util.spec_from_file_location('shared', ROOT/'model-tools-python/scripts/share_onnx_weights.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as tmp:
            src, dst = Path(tmp)/'src', Path(tmp)/'dst'
            src.mkdir()
            weights = np.arange(1024, dtype=np.float32).reshape(1, 1024)
            for name in ('decoder_model', 'decoder_with_past_model'):
                graph = helper.make_graph([helper.make_node('Add', ['x', 'w'], ['y'])], name,
                    [helper.make_tensor_value_info('x', TensorProto.FLOAT, [1, 1024])],
                    [helper.make_tensor_value_info('y', TensorProto.FLOAT, [1, 1024])],
                    [numpy_helper.from_array(weights, 'w')])
                model = helper.make_model(graph, opset_imports=[helper.make_opsetid('', 17)])
                model.ir_version = 9
                onnx.save(model, src/f'{name}.onnx')
            module.share_weights(src, dst)
            self.assertEqual((dst/'shared_weights.bin').stat().st_size, weights.nbytes)
            for name in ('decoder_model', 'decoder_with_past_model'):
                feed = {'x': np.ones((1, 1024), dtype=np.float32)}
                expected = ort.InferenceSession(str(src/f'{name}.onnx')).run(None, feed)[0]
                actual = ort.InferenceSession(str(dst/f'{name}.onnx')).run(None, feed)[0]
                np.testing.assert_array_equal(actual, expected)
                restored = onnx.load(dst/f'{name}.onnx')
                np.testing.assert_array_equal(numpy_helper.to_array(restored.graph.initializer[0]), weights)
            self.assertTrue((src/'decoder_model.onnx').is_file())

if __name__ == '__main__': unittest.main()
