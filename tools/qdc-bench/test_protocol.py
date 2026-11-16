import unittest
import numpy as np
from protocol import encode_tensor, decode_tensor, safe_extract
from pathlib import Path
import tempfile, zipfile

class ProtocolTest(unittest.TestCase):
    def test_float_roundtrip_preserves_negative_and_nonfinite(self):
        arr=np.array([[-10000.,np.inf],[.125,-3.]],dtype=np.float32)
        item=encode_tensor(arr)
        self.assertEqual(item['shape'],[2,2])
        np.testing.assert_array_equal(decode_tensor(item),arr)
    def test_int32_roundtrip_and_byte_count_validation(self):
        arr=np.array([[50258,50278]],dtype=np.int32)
        item=encode_tensor(arr)
        np.testing.assert_array_equal(decode_tensor(item),arr)
        item['shape']=[1,3]
        with self.assertRaises(ValueError):decode_tensor(item)
    def test_archive_escape_rejected_before_any_extraction(self):
        with tempfile.TemporaryDirectory() as d:
            file=Path(d)/'bad.zip'
            with zipfile.ZipFile(file,'w') as z:
                z.writestr('ok.onnx','ok');z.writestr('../escape','bad')
            with self.assertRaises(ValueError):safe_extract(file,Path(d)/'out')
            self.assertFalse((Path(d)/'out/ok.onnx').exists())

if __name__=='__main__':unittest.main()
