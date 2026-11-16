# 1Voice: separate QNN model quality benchmark

Purpose: measure PhoWhisper-small WER (100 Vietnamese BTC WAVs) and EnViT5/VinAI chrF (200 original source sentences each), using the actual AI Hub FP16 QNN context artifacts on SM8650. No ML Kit, TTS or complete product pipeline is involved. VinAI has one model per direction (100+100), EnViT5 shares one model across both directions.

The phone runs encoder/decoder QNN inference, keeps cross/self KV caches, and reorders beam caches locally. The final release APK runs both ASR greedy decoding and MT beam2 decoding entirely on the phone, one request per utterance. Host tokenizers/preprocessing and scoring are unchanged. Java beam2 matches the pinned Transformers4.51.3 algorithm (length penalty1, configured early stopping/forced EOS), validated against eight tiny-model traces and nine complete real-model CPU sentences including each longest target. Historical host-driven/debug traces are kept separately and are not the final timing results. ASR uses the exact production Kotlin VAD/trim/mel frozen on VPS, and the production QNN-contract greedy prompt/mask/history convention. CPU fallback is disabled. A QNN/runtime/device mismatch aborts instead of producing a CPU result labelled NPU.

**This is a quality benchmark, not proof of final app latency.** `graph_run_ms` sums phone ORT run calls, excluding tensor copy, host beam search, transport, preprocessing and load. `wall_ms` includes the ADB/network/host loop and is also not final app latency. Load/warmup are separate. For ASR and MT, `device_decode_ms` includes the full phone-side encoder/decode loop and local tensor handling, excluding host VAD/mel and transport; it is still not audio-to-speech pipeline latency. End-of-speech → first audible output <2s must later be measured inside the integrated app. The QNN runtime/ORT combination below has been built/packaging-audited; only a first-device load/inference can confirm binary compatibility.

## Prepared artifacts

VPS `/home/opc/voxbridge-mt3-20261113/qdc-bench/`:

- `qdcbench-release.apk` and `apk.sha256`: signed standalone ARM64 benchmark app, no source weights embedded.
- `models/models.json`: exact AI Hub job/model IDs, SHA256/size of every context file, wrapper paths and IO specs.
- `models/<model>/<encoder|decoder>/context/`: complete ONNX wrapper + context binary, relative paths preserved.
- `tokenizers/`: pinned originals, config and generation config (host only).
- `mt200.jsonl`, `asr-inputs/asr100.jsonl` +100 frozen float32 mel inputs (host only).
- `baseline/`: complete FP32 MT outputs and existing ASR FP32100 outputs for comparison.
- `host/`: these Python scripts. Host runtime already installed in `/home/opc/voxbridge-mt3-20261113/venv/bin/python`.
- `cpu-smoke/`: source ONNX validation, explicitly never QNN-device results.
- `asr-static-cpu100/`: full static ASR source validation.

Target **Samsung Galaxy S24 Ultra / SM8650 / Snapdragon 8 Gen 3**, QAIRT2.50. ORT Android QNN1.29.0, explicit QNN runtime2.50.0; APK package `com.voxbridge.qdcbench`, separate from product app. Use the non-debuggable release build and `adb shell cmd package compile -m speed -f com.voxbridge.qdcbench`; verify dexopt status `speed`. QDC forced the debug build to `verify` even after a successful compile command, causing much slower Java loops. Debug/host-driven timing is diagnostic only. Other chips require a matching recompile; do not reuse silently.

## When QDC is available

Need an SM8650 Android device and its QDC **ADB connection/tunnel instructions**. A web-only screen without ADB/file transfer is not enough for this bridge. No QDC booking has been made. Run the following on the machine with QDC ADB access and the bundle. If that machine is the VPS, the existing Python environment is ready. Otherwise copy the bundle there and install `host/requirements.txt` in a venv.

```sh
# If QDC exposes an ADB TCP address, first use its supplied connect/tunnel instructions.
# SERIAL is the verified device ID from `adb devices`, not an account credential.
python host/device.py --bundle . --serial SERIAL --token-file .session-token
```

Keep the benchmark activity visible. `device.py` verifies device/SoC, verifies local+device SHA256, pushes only context files, installs APK, starts the authenticated loopback bridge and forwards localhost18765 → phone8765. It does not open network ports on the phone. Store `.session-token` privately; it is regenerated for each launch.

First test **two cases/model**, not all500 records before discovering a runtime failure:

```sh
python host/run.py --bundle . --out results/qnn-smoke --token-file .session-token --limit 2
python host/score.py --bundle . --results results/qnn-smoke
```

If model loading or output quality fails, stop and preserve the error/profile. Do not switch to CPU and label it QNN. When smoke outputs are sound:

```sh
python host/run.py --bundle . --out results/qnn-btc --token-file .session-token
python host/score.py --bundle . --results results/qnn-btc
```

Expected counts: PhoWhisper-small100, EnViT5200, VinAI VI→EN100, VinAI EN→VI100. Resume by running the same command/output directory: completed IDs are kept, no scored row overwritten; a model/dataset/device change is rejected. Failed device runs abort and preserve prior rows.

`summary.json` contains WER (lower better), chrF and direction breakdown (higher better), exact text/token agreement with FP32, blanks/token caps, graph p50/p95 and wall p50/p95. Partial/smoke runs are explicitly incomplete. MT reference translations are AI-draft references, so chrF is not percent correct; critical number/negation/action mistakes still need review.

ORT execution profiles are recorded on device. After each successful model, their paths are saved in `<model>-profiles.json`; pull those files with ADB for NPU assignment/per-op verification. Device metadata, model/source hashes and timing scope are frozen in `run.json`.

## Development validation

```sh
python -m unittest discover -s tools/qdc-bench -p 'test_*.py'
javac -d build/qdc-unit android/qdcbench/src/main/java/com/voxbridge/qdcbench/TensorOps.java tools/qdc-bench/test_tensor_ops.java
java -cp build/qdc-unit test_tensor_ops
ANDROID_HOME=/home/opc/android-sdk ./gradlew :android:qdcbench:assembleRelease
```

`test_mt_runtime.py` compares whole beam2 token sequences with original tiny T5/mBART checkpoints, including cache reorder/duplicate behavior and bounded live cache counts. `cpu_bridge.py` exercises the same host decoder against source ONNX for parity; it cannot test QNN kernel fidelity. APK build and CPU tests never count as a successful Snapdragon run.
