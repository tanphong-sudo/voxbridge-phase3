"""Exercise the real inspection script with tiny ZIP and external-tool stand-ins; no SDK/device."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).resolve().parents[2] / "tools/dev/inspect-apk.sh"


class InspectApkTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.build_tools = self.root / "build tools"
        self.build_tools.mkdir()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.log = self.root / "adb.jsonl"
        self.apk = self.root / "test package.apk"
        with zipfile.ZipFile(self.apk, "w") as archive:
            for name in ("phowhisper-base-onnx", "whisper-small-onnx"):
                archive.writestr(f"assets/models/speech_to_text/{name}/encoder_model.onnx", b"fixture")
        self.executable(self.build_tools / "aapt2", '#!/bin/sh\nexit 0\n')
        self.executable(self.build_tools / "apksigner", '#!/bin/sh\nexit "${SIGN_EXIT:-0}"\n')
        self.executable(self.bin / "adb", f'#!{sys.executable}\n' + '''import json, os, sys
with open(os.environ["ADB_TEST_LOG"], "a") as f:
    f.write(json.dumps(sys.argv[1:]) + "\\n")
if "devices" in sys.argv:
    print("List of devices attached\\nfixture-serial\\tdevice")
if "get-state" in sys.argv:
    print(os.environ.get("ADB_TEST_STATE", "device"))
''')
        # Portable checksum stand-in avoids requiring GNU tools on macOS.
        self.executable(self.bin / "sha256sum", f'#!{sys.executable}\n' +
                        'import hashlib,sys\nprint(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())\n')
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        ADB_TEST_LOG=str(self.log))

    def executable(self, path, contents):
        path.write_text(contents)
        path.chmod(0o755)

    def run_script(self, *extra):
        return subprocess.run(["bash", str(SCRIPT), str(self.apk), str(self.build_tools), *extra],
                              env=self.env, text=True, capture_output=True, timeout=10)

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def test_default_inspection_never_contacts_adb_even_with_device_available(self):
        run = self.run_script()
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertIn("ASR directories:", run.stdout)
        self.assertEqual(self.calls(), [])

    def test_explicit_install_targets_only_requested_serial_after_inspection(self):
        run = self.run_script("--install-device", "fixture-serial")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(self.calls(), [["-s", "fixture-serial", "get-state"],
                                        ["-s", "fixture-serial", "install", str(self.apk)]])

    def test_invalid_arguments_never_install(self):
        for args in (("--unknown",), ("--install-device",), ("--install-device", "--unknown")):
            with self.subTest(args=args):
                self.assertEqual(self.run_script(*args).returncode, 2)
                self.assertEqual(self.calls(), [])

    def test_signature_failure_prevents_explicit_install(self):
        self.env["SIGN_EXIT"] = "9"
        self.assertNotEqual(self.run_script("--install-device", "fixture-serial").returncode, 0)
        self.assertEqual(self.calls(), [])

    def test_unauthorized_device_does_not_receive_install(self):
        self.env["ADB_TEST_STATE"] = "unauthorized"
        self.assertNotEqual(self.run_script("--install-device", "fixture-serial").returncode, 0)
        self.assertEqual(self.calls(), [["-s", "fixture-serial", "get-state"]])


if __name__ == "__main__":
    unittest.main()
