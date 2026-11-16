# ONNX integration — legacy OPUS route

> Android default hiện là ML Kit. Tài liệu này chỉ giữ chi tiết ONNX/native cho tuyến cũ và export; cài hiện tại theo [ML Kit](mlkit-device.md).

> The exact sequence to go from a fresh clone to a running app. The verified pure-Kotlin core
> (decision engine + safety layer) needs nothing here — it runs today via the harnesses. This
> document is the **integration glue**: export models, place them, build the Android app, run.
>
> **Honesty contract.** The two model-bound decode seams (Whisper greedy decode, OPUS-MT past-KV)
> are **implemented and verified against real exported ONNX artifacts** using optimum seq2seq
> semantics — see §4 for what was proven where. The short
> remaining list is exactly what only a physical Snapdragon phone can answer, marked ⚠️.

**Two ways to run everything below.** Pick one; both produce the same artifacts:

| Path | What | Where |
|---|---|---|
| **Colab (recommended — nothing to install)** | `colab/01_models_pipeline.ipynb` (models + JVM e2e) → `colab/02_build_apk.ipynb` (JDK + SDK + Gradle + APK) | `colab/README.md` |
| Local machine | `bash model-tools-python/scripts/fetch_all.sh` then a local Gradle build | §1, §3 below |

---

## 0. What runs *today*, with zero downloads

```bash
bash tools/self-verify/run.sh         # core decision/safety regression suite
bash tools/safety-benchmark/run.sh    # safety oracle + UNSAFE-PASS gate
bash tools/dsp-verify/run.sh          # 8/8  — Whisper log-mel vs reference
```

These compile the core with `kotlinc` and exercise the real `DecisionEngine`. No models, no device,
no Gradle.

Once models exist (§1), one more harness runs the **real app runtime Kotlin against the real ONNX
graphs** on a plain JVM — no stubs:

```bash
bash tools/e2e-jvm/run.sh models
# VAD: 30 s silence -> speech prob < 0.5        (real SileroVad recurrent session)
# Whisper: WhisperAsr.load + bounded decode on silence -> blank + high noSpeechProbability
# OPUS-MT vi->en / en->vi: past-KV greedy decode -> non-blank + translationScore in (0,1]
```

This harness compiles `core/` + the unmodified `runtime/` Kotlin (only `android.util.Log` is
shimmed) with the desktop ONNX Runtime **1.20.0** — the same version as
`onnxruntime-android-qnn` in the app — so Kotlin/Python contract drift is caught on Colab,
**before** the APK is built. (It caught one real bug already: the Silero VAD v5 state
output name mismatch, fixed in `runtime/SileroVad.kt`.)

---

## 1. Export the models

**Colab:** run `colab/01_models_pipeline.ipynb` when the selected model cache must be rebuilt. It
creates/reuses PhoWhisper Base + Whisper Small together with VAD/MT/TTS, runs one JVM E2E, and only
then promotes the cache to Drive. Then run `colab/02_build_apk.ipynb`; it restores/reuses that cache,
creates a selected ASR only if one is missing, runs JVM E2E against the exact staged APK assets,
builds the APK, verifies the final ZIP/native contents, and copies it to Drive.

**Local (one command):**

```bash
python3 -m venv .venv && source .venv/bin/activate
pip install -r model-tools-python/requirements.txt
bash model-tools-python/scripts/fetch_all.sh
```

This writes, into `models/` exactly as `models/MODEL_MANIFEST.md` specifies:

| Artifact | Status |
|---|---|
| Silero VAD (`vad/silero_vad.onnx`) | ✅ runs now (GitHub raw first — the HF mirror now returns 401 anonymously) |
| OPUS-MT VI→EN + EN→VI → ONNX → INT8 (+ `io.json`) | ✅ selected Phase-2 set |
| PhoWhisper Base (VI) + Whisper Small (EN), ONNX + tokenizer/tokens + mel filterbank | ✅ selected Phase-2 pair |
| Piper VI/EN voice bundles + ASK_REPEAT/NO_SPEECH PCM cache | ✅ selected Phase-2 set |

---

## 2. Model delivery to the device — two paths, zero manual steps on the Colab one

**Path A (turnkey, default — used by `colab/02_build_apk.ipynb`):** the notebook copies the
exported `models/` tree into `android/app/src/main/assets/models/`, so the APK **carries the
whole model set**. On first launch `runtime/ModelInstaller` unpacks it to
`getExternalFilesDir(null)/models` (atomic per file, resumable if killed mid-extract) and the
app proceeds — the phone flow is **install APK → open app**, no adb, no network (assets are
local APK content, so the no-INTERNET guarantee is untouched).

**Path B (adb push, alternative):** models are not bundled; push them manually:

```bash
# adb; the app loads from getExternalFilesDir(null)/models  (runtime/ModelPaths.kt, anchored
# by AndroidModelPaths.from(context))
DEST=/sdcard/Android/data/com.voxbridge/files/models
adb shell mkdir -p $DEST
adb push models/vad             $DEST/
adb push models/translation     $DEST/
adb push models/speech_to_text  $DEST/
adb push models/text_to_speech  $DEST/
```

In both paths, an incomplete set (or a failed extraction) shows the setup screen listing exactly
what's missing (`ModelPaths.missing()`), rather than crashing.

---

## 3. Build & run the Android app

**Colab:** `colab/02_build_apk.ipynb` installs JDK 17 + Android SDK 34 + Gradle 8.9, locates the
models zip from notebook 01 automatically (/content or Drive), **bundles the models into the APK
assets**, fetches the
matching sherpa-onnx static-runtime AAR (`sherpa-onnx-static-link-onnxruntime-1.13.3.aar` — same
1.13.3 line as the pip wheel used for the safety cache),
builds `app-debug.apk`, and **fails the build if the APK declares INTERNET**.

**Local:** no gradle wrapper is committed; AGP 8.5.0 needs Gradle **8.7+** (8.9 verified on Colab):

```bash
# sherpa-onnx-static-link-onnxruntime-1.13.3.aar -> android/app/libs/
gradle :android:app:assembleDebug --no-daemon
adb install android/app/build/outputs/apk/debug/app-debug.apk
```

The static-link sherpa AAR is intentional: the regular 1.13.3 AAR carries ONNX Runtime 1.24.3,
while the app's `onnxruntime-android-qnn:1.20.0` supplies the Java JNI/core pair used by Whisper.
For `arm64-v8a`, the static AAR keeps sherpa's ORT inside its JNI library, leaving exactly one
dynamic `libonnxruntime.so` in the arm64-only APK and avoiding an ABI/versioned-symbol collision.
The upstream static AAR also contains an x86-only `libonnxruntime.so`; the app's arm64 ABI filter
excludes that compatibility entry from the APK.

Toggle airplane mode ON before the demo — the app has **no INTERNET permission** (see
`AndroidManifest.xml`), so 100% on-device is build-enforced, not just promised.

---

## 4. Verification ledger (what is proven, where, and what remains)

Every formerly "confirm-on-first-real-model" seam is now **implemented against the real artifact
sources and machine-verified** — nothing is left as a guess:

| # | Seam | Implemented | Verified where |
|---|---|---|---|
| 1 | Whisper log-mel DSP (80×3000) | `runtime/MelSpectrogram.kt` (real DSP; filterbank file from the export) | `tools/dsp-verify` 8/8 (bit-level vs reference) |
| 2 | Whisper greedy decode — **AI Hub QNN contract** (mask cumulative zeroing, position_ids, KV echo/chaining, vocab-axis argmax, forced language after SOT) | `runtime/WhisperAsr.kt` `decodeAiHub` | 707-assertion mock suite vs the AI Hub `app.py` reference (exact-port identity checks) |
| 3 | Whisper decode — **optimum contract** (past-free bootstrap emits both KV parts; with-past chains decoder part) | `runtime/WhisperAsr.kt` `decodeOptimum` | same suite (incl. call-2 chaining + degenerate path) |
| 4 | OPUS-MT past-KV bind + translationScore | `runtime/OnnxTranslator.kt` (introspection, 3-part name parse) | same suite; plus real-graph e2e via `tools/e2e-jvm` (notebook 01, cell "E2E JVM") |
| 5 | Silero VAD v4/v5 streaming state | `runtime/SileroVad.kt` — **input≠output names** (`state`→`stateN`), caught by the e2e harness on the real v5 graph | `tools/e2e-jvm` PASS (silence prob 6e-4) |
| 6 | BPE decode (QNN id-space) + token registry | `runtime/WhisperBpeDecoder.kt` + `whisper_tokens.json` | mock suite vs HF tokenizer reference values |

**⚠️ Only these remain — each needs the physical device / real account, by nature:**

1. QNN EP session creation on-device (`*_ctx.onnx` + `libQnnHtp.so` on the Hexagon HTP) — desktop
   JVM cannot load an HTP backend; the app's `warmUp()` is the on-device probe.
2. End-to-end latency on a Snapdragon 8 Gen 3 (RTF, warm-start) — design targets only until measured.
3. Piper voice quality through the app's AudioTrack on-device (sherpa-onnx JNI loads only on Android).

---

## 5. Where everything lives

```text
core/                     verified pure-Kotlin decision engine + safety layer (no Android deps)
tools/self-verify/        70/70 harness (kotlinc)
tools/safety-benchmark/   51-case safety scorecard (kotlinc)
tools/dsp-verify/         8/8 mel DSP harness
tools/e2e-jvm/            real runtime Kotlin × real ONNX graphs on a JVM (run.sh + Main.kt)
colab/                    01_models_pipeline.ipynb + 02_build_apk.ipynb + README (the no-install path)
models/MODEL_MANIFEST.md  the source-of-truth contract (paths, licenses, tensor I/O)
model-tools-python/       fetch/export/quantize scripts -> models/
android/app/              the app: runtime/ runners, audio/ capture, ui/ Compose, store/ Room
submission/phase-2/README.md       the academic submission (xelatex)
```
