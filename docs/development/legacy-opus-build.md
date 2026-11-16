# Phase-2 VI/EN build

Notebook 02 uses `SMOKE_VI_EN`. Open the latest GitHub notebook, then Run all;
a notebook already open in Colab does not refresh its cells when the checkout is pulled.
The notebook creates only missing PhoWhisper Base / Whisper Small exports. It does not
re-export OPUS/Piper, quantize ASR or submit QAI jobs. Successful selected ASRs are cached
on Drive after the real JVM E2E gate passes.

The selected set follows `submission/phase-2/final/1V_P2_TechProposal_VoxBridge_Submission_Final.docx`:
Silero, VinAI PhoWhisper Base for VI, OpenAI Whisper Small for EN, INT8 OPUS vi-en/en-vi,
and Piper VI/EN. Whisper Tiny and Whisper Base are not product models and are never staged
into the APK.

## Required Drive cache

Under `MyDrive/VoxBridge/models/`:

- `vad/silero_vad.onnx`
- `speech_to_text/phowhisper-base-onnx/`
- `speech_to_text/whisper-small-onnx/`
- `translation/opus-mt-vi-en/`
- `translation/opus-mt-en-vi/`
- `text_to_speech/piper/vits-piper-vi_VN-vais1000-medium/`
- `text_to_speech/piper/vits-piper-en_US-lessac-medium/`
- `text_to_speech/cache/{ask_repeat,no_speech}_{vi,en}.{pcm,sr}`

If an ASR is missing, Notebook 02 creates it in an isolated Python 3.11 environment
from `vinai/PhoWhisper-base` or `openai/whisper-small`, recording the HF revision.
The CPU exports remain FP32. Identical initializers share `shared_weights.bin` across
graphs; each tensor is verified byte-for-byte on reload. This reduces duplicated decoder
storage without changing the KV contract or introducing quantization.

Each ASR needs `encoder_model.onnx`, `decoder_model.onnx`, `decoder_with_past_model.onnx`,
`whisper_tokens.json`, `mel_80_201.f32.bin`, and `vocab.json` or `tokenizer.json`.
Keep any referenced ONNX external-data sidecars with their graph.
The runtime currently uses the CPU Optimum contract and float32 input features;
AI Hub encoder/profile artifacts alone are not interchangeable with this full decoder set.
Do not rename a Tiny export to satisfy these paths.

Each MT direction needs all three `*_quantized.onnx` graphs, `source.spm`, `target.spm`,
and `vocab.json`. Each Piper directory needs its ONNX voice, tokens and espeak-ng-data.

`tools/dev/model_bundle.py` validates this set before copying, preserves the source cache,
and stages only selected language directories. Missing non-ASR models stop the notebook before SDK downloads/build. Missing ASRs are
created once; no Tiny fallback occurs. Full validation runs again before real JVM E2E.

## Verification and output

Real JVM E2E runs against the exact staged APK assets, testing both ASRs and both MT
directions. Missing graphs fail. Existing silence and translation assertions remain.
This verifies graph contracts, not speech WER, translation quality or phone installation.

The APK targets arm64-v8a, Android 10+, and is copied to
`MyDrive/VoxBridge/artifacts/apk/voxbridge-debug-SMOKE_VI_EN-<timestamp>.apk`.
The copy is checked with a streaming SHA-256 hash. Actual bundled sizes are printed.
No fixed APK-size promise: PhoWhisper Base + Whisper Small are measured from the actual staged
artifacts at build time.

The app exposes only VI/EN. It closes the previous ASR/MT direction before loading the
next one. First use of each direction incurs model loading/warm-up.

Phone acceptance remains: install, airplane mode, VI speech to EN audio, then EN speech
to VI audio. Passing JVM tests alone does not establish that milestone.
