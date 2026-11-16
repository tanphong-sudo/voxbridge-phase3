"""Regression checks for Colab packaging and fail-closed APK verification."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from package_colab import ROOT, OUT, package


class ColabTests(unittest.TestCase):
    def test_legacy_repo_zip_bootstrap(self):
        package()
        with tempfile.TemporaryDirectory() as directory:
            content = Path(directory)
            shutil.copy(OUT, content / 'voxbridge.zip')
            for notebook in (ROOT / 'colab/01_models_pipeline.ipynb', ROOT / 'colab/02_build_apk.ipynb'):
                cells = json.loads(notebook.read_text())['cells']
                source = ''.join(''.join(cell['source']) for cell in cells)
                self.assertIn('/content/voxbridge', source)

    def test_model_and_apk_notebooks_bootstrap_their_own_runtime(self):
        for name in ('01_models_pipeline.ipynb', '02_build_apk.ipynb'):
            with self.subTest(notebook=name):
                notebook = json.loads((ROOT / 'colab' / name).read_text())
                source = '\n'.join(''.join(cell['source']) for cell in notebook['cells'])
                self.assertIn('drive.mount("/content/drive")', source)
                self.assertIn('userdata.get("GITHUB_TOKEN")', source)
                self.assertIn('run_git(["clone", "--branch", "main"', source)
                self.assertNotIn('Run colab/00_dev_bootstrap.ipynb first', source)

    def test_models_notebook_does_not_export_obsolete_tiny(self):
        notebook = json.loads((ROOT / 'colab/01_models_pipeline.ipynb').read_text())
        source = '\n'.join(''.join(cell['source']) for cell in notebook['cells']).lower()
        self.assertNotIn('whisper-tiny', source)
        self.assertNotIn('whisper_tiny', source)
        self.assertNotIn('whisper_aihub.py', source)
        e2e_code_cells = [cell for cell in notebook['cells']
                          if cell.get('cell_type') == 'code' and
                          'tools/e2e-jvm/run.sh' in ''.join(cell.get('source', []))]
        self.assertEqual(len(e2e_code_cells), 1)
        self.assertIn('.colab-model-e2e-ok', source)

    def test_dev_bootstrap_is_secret_safe_and_model_preserving(self):
        notebook = json.loads((ROOT / 'colab/00_dev_bootstrap.ipynb').read_text())
        source = '\n'.join(''.join(cell['source']) for cell in notebook['cells'])
        self.assertIn('userdata.get("GITHUB_TOKEN")', source)
        self.assertNotIn('github_pat_', source)
        self.assertNotIn('ghp_', source)
        self.assertIn('run_git(["pull", "--ff-only", "origin", "main"]', source)
        update = next(''.join(cell['source']) for cell in notebook['cells'] if 'UPDATE SOURCE + TEST -' in ''.join(cell['source']))
        self.assertIn('sync_source()', update)
        self.assertNotIn('restore-models.sh', update)
        self.assertIn('tools/e2e-jvm/run.sh', update)

    def test_vi_en_build_runs_e2e_on_staged_assets_after_jdk(self):
        cells = json.loads((ROOT / 'colab/02_build_apk.ipynb').read_text())['cells']
        source = [''.join(c['source']) for c in cells]
        stage = next(i for i, s in enumerate(source) if 'assets = REPO' in s)
        jdk = next(i for i, s in enumerate(source) if "openjdk-17-jdk-headless" in s)
        self.assertLess(jdk, stage)
        self.assertIn('"tools/e2e-jvm/run.sh", str(assets)', source[stage])
        self.assertNotIn('shutil.copytree(src, dst', '\n'.join(source))
        self.assertIn('sha256_file(out) != digest', '\n'.join(source))
        self.assertNotIn('apk.read_bytes()', '\n'.join(source))
        joined = '\n'.join(source)
        self.assertIn('phowhisper-base-onnx', joined)
        self.assertIn('whisper-small-onnx', joined)
        self.assertIn('Wrong staged ASR set', joined)
        self.assertIn('Forbidden obsolete ASR staged into APK assets', joined)
        self.assertIn('APK ASR set is not exactly PhoWhisper Base + Whisper Small', joined)
        self.assertIn('APK contains obsolete Whisper Tiny/Base assets', joined)
        self.assertIn('target.parent.mkdir(parents=True, exist_ok=True)', joined)
        self.assertIn('Promoted Drive ASR differs', joined)
        self.assertIn('Removed obsolete Drive ASR cache', joined)

    def test_android_ort_packaging_keeps_one_compatible_dynamic_runtime(self):
        gradle = (ROOT / 'android/app/build.gradle.kts').read_text()
        self.assertIn('abiFilters += "arm64-v8a"', gradle)
        self.assertIn('onnxruntime-android-qnn:1.20.0', gradle)
        self.assertIn('sherpa-onnx-static-link-onnxruntime-1.13.3.aar', gradle)
        self.assertNotIn('pickFirsts += "**/libonnxruntime.so"', gradle)
        self.assertNotIn('include" to listOf("sherpa-onnx-*.aar")', gradle)

        notebook = json.loads((ROOT / 'colab/02_build_apk.ipynb').read_text())
        source = '\n'.join(''.join(cell['source']) for cell in notebook['cells'])
        self.assertIn("ASSET = 'sherpa-onnx-static-link-onnxruntime-1.13.3.aar'", source)
        self.assertIn("TARGET_ABI = 'arm64-v8a'", source)
        self.assertIn("target_ort_libs = sorted(n for n in ort_libs if n.startswith(f'jni/{TARGET_ABI}/'))", source)
        self.assertIn("assert not target_ort_libs", source)
        self.assertNotIn("assert not ort_libs", source)
        self.assertIn('expected exactly one libonnxruntime.so', source)
        self.assertIn('expected exactly one libonnxruntime4j_jni.so', source)
        self.assertIn('CORE_VER', source)
        self.assertIn('VERS_1.20.0', source)
        self.assertIn('sherpa JNI still dynamically depends on libonnxruntime.so', source)
        self.assertIn(':android:app:dependencies --configuration debugRuntimeClasspath', source)
        self.assertIn(':android:app:dependencyInsight --dependency onnxruntime --configuration debugRuntimeClasspath', source)
        self.assertIn('MAX_APK_BYTES=2147483647', source)
        self.assertIn('APK exceeds the 2 GiB install-safety limit', source)
        self.assertIn('unzip -t "$APK"', source)

    def test_device_smoke_requires_real_arm64_launch_and_ready_marker(self):
        smoke = (ROOT / 'tools/dev/device-smoke.sh').read_text()
        vm = (ROOT / 'android/app/src/main/java/com/voxbridge/ui/PushToTalkViewModel.kt').read_text()
        self.assertIn('ro.product.cpu.abi', smoke)
        self.assertIn('ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"', smoke)
        self.assertIn('"$ADB" install -r "$APK"', smoke)
        self.assertIn('am start -W', smoke)
        self.assertIn('UnsatisfiedLinkError', smoke)
        self.assertIn('dlopen failed', smoke)
        self.assertIn('(VoxBridge|PushToTalkVM).*startup ready: native runners loaded', smoke)
        self.assertIn('Log.i(TAG, "startup ready: native runners loaded")', vm)

    def test_apk_gate(self):
        notebook = json.loads((ROOT / 'colab/02_build_apk.ipynb').read_text())
        code = next(''.join(cell['source']) for cell in notebook['cells']
                    if 'APKSIGNER=$BUILD_TOOLS/apksigner' in ''.join(cell['source']))
        code = code.split('\n', 1)[1]
        code = code.split('# APK model gate:', 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk, aapt, apksigner = root / 'app.apk', root / 'aapt2', root / 'apksigner'
            import zipfile
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('AndroidManifest.xml', b'fixture')
            code = code.replace('/content/voxbridge/android/app/build/outputs/apk/debug/app-debug.apk', str(apk))
            code = code.replace('/opt/android-sdk/build-tools/34.0.0', str(root))
            apksigner.write_text('#!/bin/bash\nexit 0\n')
            apksigner.chmod(0o755)
            cases = [
                ('failed aapt', 'exit 2', False),
                ('internet', "echo 'uses-permission: android.permission.INTERNET'", False),
                ('missing launcher', "echo 'package: com.voxbridge'", False),
                ('valid', "echo 'package: com.voxbridge'; echo 'launchable-activity: com.voxbridge.ui.MainActivity'", True),
            ]
            for name, body, success in cases:
                with self.subTest(name=name):
                    aapt.write_text('#!/bin/bash\n' + body + '\n')
                    aapt.chmod(0o755)
                    result = subprocess.run(['bash', '-c', code], capture_output=True, text=True)
                    self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)

    def test_apk_asr_gate_accepts_only_pho_and_whisper_small(self):
        notebook = json.loads((ROOT / 'colab/02_build_apk.ipynb').read_text())
        code = next(''.join(cell['source']) for cell in notebook['cells']
                    if 'APKSIGNER=$BUILD_TOOLS/apksigner' in ''.join(cell['source']))
        gate = code.split('# APK model gate:', 1)[1].split('\n', 1)[1].split('# Native ORT gate:', 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / 'fixture.apk'

            def run(names):
                import zipfile
                with zipfile.ZipFile(apk, 'w') as archive:
                    for name in names:
                        archive.writestr(name, b'x')
                return subprocess.run(
                    ['bash', '-c', 'set -euo pipefail\n' + gate],
                    env={**os.environ, 'APK': str(apk)}, capture_output=True, text=True,
                )

            required = [
                'assets/models/speech_to_text/phowhisper-base-onnx/encoder_model.onnx',
                'assets/models/speech_to_text/whisper-small-onnx/encoder_model.onnx',
            ]
            self.assertEqual(run(required).returncode, 0)
            self.assertNotEqual(run(required + [
                'assets/models/speech_to_text/whisper-tiny-onnx/encoder_model.onnx']).returncode, 0)
            self.assertNotEqual(run(required + [
                'assets/models/speech_to_text/whisper-base-onnx/encoder_model.onnx']).returncode, 0)


if __name__ == '__main__':
    unittest.main()
