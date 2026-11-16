import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
import tempfile
import types

spec = importlib.util.spec_from_file_location("profile_device", Path(__file__).with_name("profile-device.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ProfileDeviceTest(unittest.TestCase):
    def test_summary_kib(self):
        self.assertEqual(module.parse_meminfo(" Java Heap: 120\n Native Heap: 240\n TOTAL PSS: 560 TOTAL RSS: 800"),
                         {"java_heap_pss_kib": 120, "native_heap_pss_kib": 240, "total_pss_kib": 560})

    def test_missing_not_zero(self):
        self.assertEqual(set(module.parse_meminfo("No process found").values()), {""})

    def test_authorization(self):
        self.assertEqual(module.authorized_devices("List of devices attached\na device\nb unauthorized\nc offline\n"), ["a"])

    def test_recording_with_fake_adb_only_reads_device(self):
        calls = []

        def fake_run(cmd, **kwargs):
            calls.append(cmd)
            if cmd[-1] == "devices":
                output = "List of devices attached\nserial device\n"
            elif "meminfo" in cmd:
                output = "TOTAL PSS: 123\n"
            elif "pidof" in cmd:
                output = "42\n"
            else:
                output = "fixture\n"
            return types.SimpleNamespace(returncode=0, stdout=output, stderr="")

        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "recording"
            with patch("sys.argv", ["profile-device.py", "--output", str(output), "--turns", "1"]), \
                 patch.object(module.subprocess, "run", side_effect=fake_run), \
                 patch("builtins.input", side_effect=["cold", "VI success"]), \
                 patch("builtins.print"):
                module.main()
            self.assertIn("123", (output / "samples.csv").read_text())
            self.assertTrue((output / "001.thermal.txt").exists())
            self.assertTrue((output / "001.logcat.txt").exists())
        self.assertFalse(any(token in cmd for cmd in calls for token in ["install", "push", "rm", "am", "pm", "reboot"]))


if __name__ == "__main__":
    unittest.main()
