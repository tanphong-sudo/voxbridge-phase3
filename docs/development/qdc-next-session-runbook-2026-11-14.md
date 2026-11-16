# QDC session runbook — 2026-11-14

## What is ready

**Do not open a new QDC session yet.** The package below is ready only for standalone model diagnostics. It is insufficient for the requested complete app latency acceptance. Use these commands only when the missing integrated runtime is ready, or if the user explicitly chooses a standalone diagnostic session.

Standalone diagnostic artifact:

- Package: `com.voxbridge.qdcbench`
- APK: `qdcbench-release.apk`
- Target: Snapdragon 8 Gen 3 / SM8650
- ORT QNN: 1.29.0 with QNN runtime 2.50.0
- Execution: QNN HTP with CPU fallback disabled
- Models: PhoWhisper-small, EnViT5, VinAI VI→EN and VinAI EN→VI
- Frozen corpus: PhoWhisper 100 VI WAVs; MT 200 rows

This benchmark is the verified QNN path. The product app still has ML Kit in `PushToTalkViewModel`; uploading the product APK would therefore measure the wrong translator. Do not use it for the QNN model numbers.

## QDC operator sequence

1. Open a new QDC session for QRD8650 / SM8650.
2. Choose **SSH only** so ADB tunneling is available.
3. Upload `qdcbench-release.apk` as an **Application**.
4. Use the QDC private key with `chmod 400`.
5. Start the QDC SSH tunnel shown by the session, then confirm `adb devices` has exactly one authorized device.
6. Put the prepared bundle on the machine with ADB access and run:

```sh
python host/device.py --bundle . --serial <QDC_SERIAL> --token-file .session-token
```

7. Keep the benchmark activity open. Run a two-row smoke test first:

```sh
python host/run.py --bundle . --out results/qnn-smoke --token-file .session-token --reuse-buffers --limit 2
python host/score.py --bundle . --results results/qnn-smoke
```

8. If loading, execution backend and output checks are green, run the complete frozen set:

```sh
python host/run.py --bundle . --out results/qnn-btc --token-file .session-token --reuse-buffers
python host/score.py --bundle . --results results/qnn-btc
```

Expected rows: PhoWhisper-small 100, EnViT5 200, VinAI VI→EN 100, VinAI EN→VI 100. The command is resumable and refuses a changed device/model/dataset manifest.

## What the QNN benchmark answers

It gives phone-side ASR/MT decode timing, QNN backend proof, model output quality against the frozen references, and per-op profiles. It does not answer release-to-first-speaker latency because it does not own microphone capture, safety, TTS and AudioTrack.

The product app measurement must be a later QDC pass with release-to-first-`AudioTrack.write` timestamps. The app now starts its timing clock at PTT release, before capture finalization, so the wait includes audio drain/resampling. A displayed timing is `Chưa phát âm thanh` until the first PCM write callback actually fires.

## Parakeet v2 status

Pinned candidate: `nvidia/parakeet-tdt-0.6b-v2`, revision `ae9ad07059c7c739ffaf932226a8fe64ae2620b0`, checkpoint SHA256 `d99e39955c9d3d0350d8fb7c75e40c64a2b2eaeb003883d7c941fd2e8747b28c`.

BTC100 CPU result: normalized WER 7.07% (127 edits / 1,797 reference words), warm p50 about 798 ms. It has not been exported to a validated AI Hub/QNN Android graph and is not wired into the app. The QDC session must not claim Parakeet QNN results until that export and phone run exist.

## Current latency reality

Existing standalone QNN results are not an end-to-end pass: PhoWhisper-small p50 about 711 ms; EnViT5 p50 about 723 ms; VinAI directions p50 about 883 ms and 1,250 ms; Piper CPU first-callback measurements were roughly 577 ms VI and 473 ms EN at three threads. These numbers cannot be summed as a proof of `<2s`. The integrated QDC measurement is the release criterion.
