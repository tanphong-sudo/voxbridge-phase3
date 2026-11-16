# VoxBridge

Offline push-to-talk speech translator between Vietnamese and English for construction sites, running fully on a Snapdragon 8 Gen 3 phone. Team T&P, OneVoice AI Challenge 2026, Phase 3 prototype.

Hold the button, say one sentence, release: the phone recognizes the speech, translates it, checks numbers, negation and hazard words, and speaks the translation in the other language. No network is used at any point.

## Pipeline

| Step | Model / method | Runs on |
|---|---|---|
| Voice activity | Silero VAD v5 | CPU (ONNX Runtime) |
| Speech recognition | PhoWhisper-small (VI), Parakeet-TDT-0.6B-v2 (EN), static graphs, FP16 | Hexagon NPU (ONNX Runtime 1.29 QNN EP, QAIRT 2.50) |
| Text clean-up | stutter collapse; construction glossary pins 98 trade terms before VI→EN | CPU |
| Translation | VietAI EnViT5 (T5-base), static encoder + KV-cache decoder, FP16, beam 2 | Hexagon NPU |
| Safety check | rule-based: numbers, units, negation, hazards, equipment; repair, confirm or ask to repeat | CPU |
| Speech output | Piper: en_US lessac-medium and vi vais1000; text frontend on CPU, vocoder FP16 on NPU | CPU + NPU |

All NPU graphs are compiled ahead of time for SM8650 with Qualcomm AI Hub. Qualcomm AI Hub and Qualcomm Device Cloud are development tools only and are not used at runtime.

## Results

Snapdragon 8 Gen 3 (SM8650) on Qualcomm Device Cloud, 200 BTC voice clips fed as WAV files through the app's pipeline. Raw rows, memory traces and scores: [evidence/phase-3](evidence/phase-3/README.md).

| | VI→EN | EN→VI |
|---|---|---|
| First translated audio ≤ 2 s | 99/100 | 99/100 |
| Latency p50 / p95 / max | 766 / 1077 / 1354 ms | 588 / 1003 / 1125 ms |
| ASR WER | 3.68% | 6.83% |
| Translation chrF | 49.7 | 51.1 |
| Peak app memory (PSS) | 582 MiB | 421 MiB |

Model files 2.66 GB, APK 138 MB.

## Demo video

[YouTube](https://youtu.be/z5bSHIXGLkI) (also as voxbridge-p3-demo.mp4 on the release page) (3:50, one uncut take on the Snapdragon 8 Gen 3 device in airplane mode). Our team does not own a Snapdragon 8 Gen 3 phone, so we used a remote Qualcomm Device Cloud phone; it sits in a server rack, so speech input is synthetic voice clips fed as its microphone; everything after the button release runs on the phone. Script: [demo-script.md](submission/phase-3/demo-script.md).

## Install the offline package

The package is published as three zip files on the [release page](https://github.com/tanphong-sudo/voxbridge-phase3/releases/tag/p3-final). Unzip all three into the same folder; you get `voxbridge-p3/` with the APK, `models/` and `install.sh`.

```bash
cd voxbridge-p3
bash install.sh            # or: bash install.sh <adb-serial>
```

The script installs the APK, copies the models to `/sdcard/Android/data/com.voxbridge/files/models`, gives the app ownership of the files and grants the microphone permission. Then turn on airplane mode and open VoxBridge. The first launch verifies every model file against `integrity.sha256` (about one minute) before the "Giữ để nói" button appears.

Requirements: Snapdragon 8 Gen 3 (SM8650) phone, Android 14 (tested), about 3.5 GB free storage, a computer with adb.

## Build from source

JDK 17 and the Android SDK are needed. The repository does not contain model weights.

```bash
./gradlew :core:test :android:app:assembleQdcPipeline
```

Model export and compile scripts are in `model-tools-python/qaihub/`.

## Repository layout

| Folder | Contents |
|---|---|
| `android/` | App, UI, on-device runtimes (ASR, MT, TTS, VAD) and tests |
| `core/` | Decision engine, safety check, text normalization |
| `domain-packs/construction_vi_en/` | Construction glossary (98 VI terms → EN) |
| `experiments/construction_vocab/` | Trade-term translation check and glossary results |
| `model-tools-python/` | Model export, AI Hub compile and parity scripts |
| `tools/`, `scripts/` | Benchmarks, scoring and developer tools |
| `evidence/phase-3/` | On-device benchmark results used in the report |
| `submission/phase-3/` | Report, demo script, install script |
| `docs/` | Development and test notes; some describe earlier designs (ML Kit, OPUS) that the final build no longer uses |
