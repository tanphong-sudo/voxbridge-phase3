"""Real filesystem/git-apply boundary tests; no native runtime or inference."""
import difflib
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

import stage as stager


def sha(data):
    return hashlib.sha256(data).hexdigest()


class StageTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.server,self.repo,self.package = [self.root/name for name in ('server','repo','package')]
        self.work = self.root/'isolated'
        self.metadata = dict(server_base_sha256={},patched_sha256={},canonical_helpers_sha256={},
                             tested_artifacts_sha256={})
        patch = []
        for index,path in enumerate(stager.CORE_PATCH_FILES):
            before = f'old server {index}\n'.encode()
            after = f'new merged {index}\n'.encode()
            self.put(self.server/path,before)
            self.metadata['server_base_sha256'][path] = sha(before)
            self.metadata['patched_sha256'][path] = sha(after)
            patch.extend(difflib.unified_diff(before.decode().splitlines(True),after.decode().splitlines(True),
                                               fromfile='a/'+path,tofile='b/'+path))
        self.put(self.package/'server-core.patch',''.join(patch).encode())
        for path in stager.NEW_HELPERS:
            data = ('canonical '+path+'\n').encode()
            self.put(self.repo/path,data)
            self.metadata['canonical_helpers_sha256'][path] = sha(data)
        for path in stager.SERVER_DIRS:
            self.put(self.server/path/'Preserved.kt',b'unchanged server source\n')
        self.put(self.server/stager.LOG_SHIM,b'log shim\n')
        for path in stager.PACKAGE_FILES:
            data = ('packaged '+path+'\n').encode()
            self.put(self.package/path,data)
            self.metadata['tested_artifacts_sha256'][path] = sha(data)
        monitor = b'canonical monitor\n'
        self.put(self.repo/stager.MONITOR,monitor)
        self.metadata['monitor_process_sha256'] = sha(monitor)
        self.put(self.package/'provenance.json',json.dumps(self.metadata).encode())

    def put(self,path,data):
        path.parent.mkdir(parents=True,exist_ok=True)
        path.write_bytes(data)

    def test_fresh_staging_applies_patch_and_copies_exact_sources_without_mutating_server(self):
        before = {str(p.relative_to(self.server)):p.read_bytes() for p in self.server.rglob('*') if p.is_file()}
        stager.stage(self.server,self.repo,self.work,self.package)
        for path,expected in self.metadata['patched_sha256'].items():
            self.assertEqual(sha((self.work/'source'/path).read_bytes()),expected)
        for path,expected in self.metadata['canonical_helpers_sha256'].items():
            self.assertEqual(sha((self.work/'source'/path).read_bytes()),expected)
        for src,dest in stager.PACKAGE_FILES.items():
            self.assertEqual((self.package/src).read_bytes(),(self.work/dest).read_bytes())
        self.assertEqual((self.work/'adapter/monitor_process.py').read_bytes(),b'canonical monitor\n')
        after = {str(p.relative_to(self.server)):p.read_bytes() for p in self.server.rglob('*') if p.is_file()}
        self.assertEqual(before,after)

    def test_base_mismatch_rejects_before_creating_workdir(self):
        self.put(self.server/stager.CORE_PATCH_FILES[0],b'unreviewed server change\n')
        with self.assertRaisesRegex(ValueError,'server base SHA256 mismatch'):
            stager.stage(self.server,self.repo,self.work,self.package)
        self.assertFalse(self.work.exists())

    def test_existing_destination_and_server_nested_destination_are_rejected(self):
        self.work.mkdir()
        sentinel = self.work/'keep.txt'
        sentinel.write_text('keep')
        with self.assertRaises(ValueError):
            stager.stage(self.server,self.repo,self.work,self.package)
        self.assertEqual(sentinel.read_text(),'keep')
        with self.assertRaisesRegex(ValueError,'outside server checkout'):
            stager.stage(self.server,self.repo,self.server/'new-work',self.package)


if __name__ == '__main__':
    unittest.main()
