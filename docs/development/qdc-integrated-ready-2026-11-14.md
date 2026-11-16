# Integrated QDC candidate — 2026-11-14

This replaces the earlier **standalone-only** readiness status. The new `qdcPipeline` APK contains the real app UI and a device corpus Activity using the same runtime adapters and core speech authorization. It is prepared for an SM8650 device session; device smoke/full200 results are still required. There is no verified full-pipeline <2s claim.

## Frozen runtime

- Vietnamese ASR: PhoWhisper-small, existing FP16 QNN encoder/greedy decoder.
- English ASR: NVIDIA Parakeet TDT 0.6B v2, pinned revision `ae9ad07059c7c739ffaf932226a8fe64ae2620b0`. FP16 QNN encoder `jp1m7r78p`, one-step decoder/joint `jgdm8j8rg`. Deterministic CPU mel frontend and Java TDT host loop. No gain or unvalidated word boosting.
- Translation: both EnViT5 and VinAI VI→EN/EN→VI retained, FP16 QNN, beam2, source64/target192. EnViT5 uses the repaired stable RMS encoder. Literal reserved tokens and over-budget sources ask for a repeat instead of truncating. Model output token caps also ask for a repeat.
- TTS: original Piper voices/noise0/speed1. Dynamic phonemizer/duration/flow stays on CPU because that graph is not statically compilable as a whole. The waveform stage is QNN FP16 (`jpvne3zkg` VI, `jgj8oxkv5` EN), QAIRT2.50. Static64 blocks retain16 frames context on each side. Valid-length masks after convolutions preserve short-utterance boundaries. Explicit raw-latent generation disables silence scaling; equivalent Sherpa silence scaling runs on the completed waveform only. No CPU fallback for QNN graph inference.
- Safety: production DecisionEngine and SpeechOutputPolicy, measurement/negation/action/hazard checks, collapse handling and confirmation before repaired speech.

All QNN contexts use QAIRT2.50 and the app uses ORT Android QNN1.30. The old product release/competition build types still select legacy ML Kit; use **qdcPipeline**, not those APKs.

## Evidence before the phone session

- APK `assembleQdcPipeline` succeeds. Fresh host tests:202/202. Includes model selection, timing, safety and Piper tile transport tests.
- MT tokenizer source IDs/masks:436/436 across BTC and Unicode edge inputs, versus Rust tokenizer.json. MT CPU full-decode token parity from the runtime port:406/406.
- Parakeet CPU Java runtime:99/99 token sequences match NeMo on speech-present cases.98/99 text strings match exactly; one difference is a space before an apostrophe. WER on all100 English inputs, including one VAD skip:127/1797 =7.0673%, unchanged from native baseline. This does not establish QNN WER.
- Java mel feature numerical comparison retains3/99 strict tolerance failures (maximum absolute deviation0.00657); all99 token sequences above match. This limitation is preserved rather than changing the numerical threshold.
- Piper split dynamic frontend/vocoder reproduces both initial VI/EN test waveforms exactly. Masked fixed-length CPU graph passes16 short/full boundary cases. The broader corpus comparison is recorded separately under `piper-hybrid-corpus`.
- AI Hub actual Snapdragon vocoder tests:8 cases per language, all finite; VI tile p50/p95=10.1625/10.9111ms, EN=10.125/10.62915ms. FP16 SNR varies with signal power; these are component tests, not full TTS or end-to-end latency.
- App ModelPaths and SHA integrity validator accepts both candidate profiles against the staged806-file,4,923,242,301-byte model tree.

## Artifacts

VPS: `/home/opc/voxbridge-mt3-20261113/integrated-qdc-bundle-20261114`.

Local APK: `output/qdc-ready/voxbridge-qdc-pipeline.apk`, accompanied by `apk.sha256`. Model files remain on VPS to avoid duplicating nearly5GB on the laptop. Corpus contains200 original16kHz mono PCM16 WAVs with verified SHA256. Golden answers stay in host `references200.jsonl` and are not staged as model inputs.

`tools/qdc-bench/deploy_pipeline.py` checks the SM8650 identity, APK hash and all model/corpus files, installs the APK, pushes/verifies files and starts either candidate. Root QDC storage ownership is adjusted only within this app's files directory. Existing results are preserved; a run name and immutable APK/manifest/profile contract prevent mixing runs.

## Device sequence

1. Book Snapdragon8Gen3 / SM8650, SSH only,120minutes, upload the new integrated APK.
2. Connect the QDC ADB tunnel. Deploy from VPS or laptop against the forwarded ADB server.
3. Smoke at least one VI and one EN case, because the manifest begins with Vietnamese. A `limit2` run alone does **not** test the English route. Use a dedicated two-direction manifest or proceed with full200 after separately exercising both routes.
4. Run200 cases EnViT5, then200 VinAI, with different output names. Preparation outside the release timer remains recorded separately. Audio playback is drained between cases; transport and WAV loading are outside the timer.
5. Collect JSONL, logcat, process memory/thermal state and actual MainActivity screenshots. The corpus path measures preloaded WAV→VAD→ASR→MT→safety→TTS→first AudioTrack write; actual PTT UI logs include release/capture finalization. AudioTrack write is an acoustic onset proxy.
6. Score with `score_pipeline.py`. Report per-language WER, spoken subset chrF, failures, repeat/confirmation rates, p50/p95/max and fraction of **all inputs** producing translated audio under2s. Prompt audio never counts as a successful translated deadline.

No successful install, live microphone interaction, visual screenshot, QNN full-corpus WER/chrF or complete phone latency is claimed until that session actually runs. The current UI build has not yet been visually verified on a working device; earlier mockup illustrations are not screenshots.
