import tempfile,unittest,hashlib
from pathlib import Path
from prepare_next_session import checked_copy,child

class BundleIntegrityTest(unittest.TestCase):
 def test_tampered_source_is_rejected_without_copying(self):
  with tempfile.TemporaryDirectory() as temp:
   root=Path(temp);src=root/'src';dst=root/'dst';src.mkdir();(src/'x.bin').write_bytes(b'bad')
   row={'path':'x.bin','bytes':3,'sha256':hashlib.sha256(b'yes').hexdigest()}
   with self.assertRaisesRegex(ValueError,'Source integrity'):checked_copy(src,dst,row)
   self.assertFalse((dst/'x.bin').exists())
 def test_valid_source_repairs_a_stale_destination(self):
  with tempfile.TemporaryDirectory() as temp:
   root=Path(temp);src=root/'src';dst=root/'dst';src.mkdir();dst.mkdir()
   (src/'x.bin').write_bytes(b'new');(dst/'x.bin').write_bytes(b'old')
   checked_copy(src,dst,{'path':'x.bin','bytes':3,'sha256':hashlib.sha256(b'new').hexdigest()})
   self.assertEqual(b'new',(dst/'x.bin').read_bytes())
 def test_absolute_and_parent_escape_are_rejected(self):
  with tempfile.TemporaryDirectory() as temp:
   for name in ['../../etc/passwd','/etc/passwd']:
    with self.assertRaises(ValueError):child(Path(temp),name)
if __name__=='__main__':unittest.main()
