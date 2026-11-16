# Corrected Vietnamese ASR selection: PhoWhisper-small

User explicitly corrected base→small on2026-11-13. Selected source is `vinai/PhoWhisper-small@a86b604c346caf7148c37512eafe783a16420adb`, d_model768,12encoder/decoder layers. English remains existing Whisper Small. Old base profiles/results are historical evidence only; no claim that base represents the final pipeline.

Existing teammate exports verified on migrated VPS:
- `/home/opc/exports/speech_to_text/phowhisper-small-onnx`: FP32 with1,126,268,928-byte shared weights.
- `/home/opc/exports/speech_to_text/phowhisper-small-int8`: actual DynamicQuantizeLinear/MatMulInteger operations in all three graphs. Its source.json incorrectly still claims float32/shared storage; do not label it an AI Hub-compressed model or trust that precision metadata. Original teammate files are preserved.

Prepared exact pinned HF checkpoint in experiment cache for AI Hub-compatible export. `static_phowhisper.py` uses the existing Android AI_HUB_QNN head-major cross/self-cache layout, history199 and explicit position; it can decode a complete utterance rather than a fixed-position feasibility probe. Small random Whisper logits match original decoder across12positions. Actual checkpoint Torch/ONNX parity gates run before compilation submission. No Android app switch/ASR tuning is claimed completed merely by selecting/exporting it.

`PhosmallAsrRunner.kt` reuses production Kotlin VAD/trim/mel/decode against both existing teammate exports on all100VI WAVs. New source and result manifests record small explicitly. Base-only full-WAV comparison runs stopped after user correction; prior base W8A16 candidate had27/27token-cap/repetition regressions and was rejected, not a result about small.

MT work continues independently: full-source64/target192/beam2 static graphs pass full-sentence parity and clean BTC200 FP32 generation. Actual AI Hub quantized v2 graph pairs run the same complete test as downloaded. See `mt-static-v2-btc200-2026-11-13.md`.
