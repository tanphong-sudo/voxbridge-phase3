# TTS speed decision and competition scoring — 2026-11-13

## Scoring verified against primary brief

Read `submission/phase-3/templates/1V_P3_Prototype_Brief.pdf`, section6, printed page2: A Voice-to-Voice translation quality45%, including semantic accuracy, clarity and naturalness. B on-device performance20%, including model size, inference time and peak RAM/VRAM. No separate TTS percentage or mandatory MOS formula is specified in this section. TTS therefore matters to combined spoken-output quality and overall performance; 45% is not all allocated to TTS.

The previous handoff/tts-models notes record the user's preference for VieNeu and its earlier VI intelligibility proxy. Those are relevant, but are not a new controlled human quality score. Current Android and recent BTC200 output use Piper. This experiment does not silently revert any final user-selected TTS; Nano remains a host candidate pending native port/acceptance.

## Candidates and artifacts

Compared the exact current VI vais1000-medium / EN lessac-medium Piper FP32, official Sherpa INT8 conversions of those same voices, and existing pinned VieNeu Nano Trúc Ly at4/8 Euler steps, cfg3, sway-1, seed42. Kept guidance enabled; cfg0 may damage intelligibility. Two CPU thread settings2/4 checked. No new Turbo/MMS models downloaded. Official Piper archives downloaded to experiment-only directories, GitHub release SHA256 and byte length verified before safe extraction. Download provenance: `output/phase3/tts-speed/downloads.json`.

Nano source: `pnnbao-ump/VieNeu-TTS-v3-Nano@aba295eb96a6fa6003ebe417cc1f2802a7adc1dc`. SDK3.8.3 with local six-file model bundle, no HF network during synthesis. Isolated Python3.11 environment `/home/opc/voxbridge-mt3-20261113/tts-venv`. UI/legacy-only Gradio/librosa dependencies are omitted; used CPU preset path imports and synthesis succeeded. No project/ASR/MT environment dependency replacement. Current experimental CPU ORT1.23.2 / Sherpa1.13.3 is not an Android runtime parity result.

## Method and numbers

12 texts per language (stop/evacuate, negation, machine numbers, decimals, measurement units, questions, two sentences), 2 measured repetitions/text, warmup excluded. Full utterance synthesis, not acoustic first audio. Initial exploratory comparisons had overlapping backend processes and different ORT environments; **use the sequential confirmation below for conclusions**. Confirmation used the same isolated environment for both engines, Piper before Nano; unrelated VPS workload remains uncontrolled, so small differences are not definitive.

| Candidate | ONNX weight MB (decimal) | CPU threads | N | p50 ms | p95 ms |
|---|---:|---:|---:|---:|---:|
| Piper VI FP32 |63.1|2|24|134|196|
| Piper EN FP32 |63.1|2|24|151|237|
| Piper EN FP32 |63.1|4|24|174|338|
| Piper VI INT8 |18.6|2|24|413|672|
| Piper EN INT8 |18.6|2|24|513|908|
| Nano VI 4steps |281.7|4|24|450|626|
| Nano VI 8steps |281.7|4|24|731|942|

Sizes above exclude engine, phonemizer data, APK and peak memory; Nano full six-file runtime is281.8MB. INT8 compressed archives are21.0MB EN/21.6MB VI. Do not conflate archives with model weights. No new RAM benchmark. New measurements are not cross-model MOS or ASR roundtrip accuracy. Different voices can speak at different durations; raw RTF and durations are retained.

## Decision

Keep existing Piper FP32 / two CPU threads as speed baseline for both languages. No deployment/model/default-thread change. INT8 is smaller but roughly3x slower in this VPS CPU run, so do not promote for speed; this does not prove INT8 slower on Snapdragon. Four threads did not give stable EN gains across passes; no justification for raising Android threads. Preserve the small INT8 candidates as unpromoted device candidates rather than mislabelling their quality as bad or deleting evidence.

Nano4 is about38% faster than Nano8 here, but quality at4steps is unaccepted. The old preference for VieNeu justifies listening to Nano4/8 against Piper before choosing the overall quality/speed compromise. Keep EN Piper: official Nano card explicitly warns about weaker English. Human listening must check negation, exact machine number, units, missing words and naturalness; do not chase voice pleasantness while degrading clarity. Android port and target-device latency remain required for any Nano promotion.

Existing optimizations remain: exact full-command PCM cache and sentence-first ordered playback, safety confirmation before any speech. Model load/voice swapping still belongs in device evaluation. Do not claim total pipeline<2s from these short warmed VPS tests.

Artifacts: `output/phase3/tts-speed/` initial exploration; `output/phase3/tts-speed-confirm/` sequential confirmation, raw rows, summary and comparison WAVs. Script `tools/phase3/tts_speed_compare.py`; exact confirmation variant names recorded in output names. No ASR changes or new ASR benchmark, no APK installed, no new cloud jobs for TTS.

Primary model sources: https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models and https://huggingface.co/pnnbao-ump/VieNeu-TTS-v3-Nano/blob/aba295eb96a6fa6003ebe417cc1f2802a7adc1dc/README.md .
