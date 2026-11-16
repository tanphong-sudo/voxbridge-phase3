# Mac CPU DOCX traceability lock

Task 0 records the submitted DOCX requirements before any Mac CPU E2E runtime work. It is a documentation/traceability artifact only; it does not waive missing stages or treat host evidence as Galaxy S24 evidence.

## Current correction — 2026-10-05

The matrix and Task-0 conclusion below are a **historical snapshot**, not current status.
This correction was inspected against working-tree code based on commit
`b2310c96f07f4c7f093155141c7ed90ee3df5612` (uncommitted migration/audit fixes are not a new commit).

- MC-DOCX-008: `PiperTts.synthesize` now invokes `SpokenTextNormalizer.normalize`
  before synthesis. The historical missing-normalization finding is superseded;
  this does not establish pronunciation accuracy on hardware.
- MC-DOCX-005/010: Android now uses `MlKitTranslator` and profile `vi-en-mlkit`
  instead of OPUS-MT, following the user-approved backend change. Mac OPUS results
  remain historical and cannot represent ML Kit accuracy/latency.
- MC-DOCX-013: translation inference is on-device **after** first-time ML Kit model
  download over Wi-Fi. INTERNET permission is intentional; do not describe this as
  a network-permission-free build. Airplane-mode acceptance remains device work.
- MC-DOCX-012: Snapdragon hardware alone does not establish NPU execution. The
  currently inspected language-routed Whisper path requests CPU. Neither this
  correction nor host tests establish QNN acceleration or the `<1.6 GB` target.
- C09 device evidence remains **NOT MEASURED** in this change. Use
  [device profiling protocol](device-profiling.md) for cold/warm memory,
  100 manual turns, thermal/battery and failure observations. No performance
  threshold has been relaxed and no historical safety failure is waived.

## Historical Task-0 record (retained verbatim below)

## Document identity

Working authority requested for this plan:

- `submission/phase-2/final/1V_P2_TechProposal_VoxBridge_Submission_Final.docx`
  - SHA-256: `e4839ab4b55d42d017332b2239af88a2a83ec7eb21b518623ec8d22a056bb862`
- Comparison variant: `submission/phase-2/README.md`
  - SHA-256: `9ee4718d234ba09fdb5588785943f20e7ae8305feb2ab8e4b82b04c124b2c072`
- Comparison variant: `submission/phase-2/README.md`
  - SHA-256: `69398460ef6adb2222988b6846863eb7d5c8a5f5fc1c8205cf3cf6f7c28acc11`

The three files agree on the main VI↔EN dataflow and primary model selections. Two requirement conflicts are material enough to record explicitly:

1. `Submission_Final` says Whisper Tiny is **benchmark baseline only** and explicitly unsuitable as a product fallback; `Final_Benchmark` says Whisper Tiny remains a **low-resource fallback**.
2. `Submission_Final` changes deployment from a Galaxy-S24-only product configuration to an Android app for compatible Snapdragon phones with S24 as the reference validation device, and changes the integrated AI working-set planning target from `<1.2 GB` to `<1.6 GB`. The older final variants retain the S24-product framing and `<1.2 GB` target.

The plan already names `Submission_Final` as the working architecture authority. That authority is therefore binding for this execution: **Whisper Tiny remains benchmark-only, with no production fallback path; deployment scope is compatible Snapdragon Android phones with Galaxy S24 as the reference validation device; the integrated-device working-set planning target is `<1.6 GB`.** Mac CPU measurements remain host evidence and do not claim that device target.

## Required pipeline lock

The working chain from the submitted design is:

`audio input → VAD/audio-quality gate → language-routed ASR + reliability guard → Local Memory Lookup → NMT on miss/untrusted hit → Safety Preservation → Text Normalization → Offline TTS → audio output`

Branch semantics are part of the requirement: a trusted memory reuse must be safety-checked again; failed early gates terminate before expensive work; a repaired translation must not be spoken until the user confirms it.

## Traceability matrix

| ID | DOCX requirement | Implementation path inspected | Test / evidence ID | Result | Gap / note |
|---|---|---|---|---|---|
| MC-DOCX-001 | §4.1 deterministic state machine emits exactly `NO_SPEECH`, `ASK_REPEAT`, `MODEL_TRANSLATION`, `VERIFIED_TRANSLATION`, `REPAIRED_TRANSLATION`, `MEMORY_TRANSLATION` | `core/src/main/kotlin/com/voxbridge/core/pipeline/TranslationDecision.kt`; `DecisionEngine.kt`; `benchmark-cli/.../Main.kt` | `T0-STATIC-DECISIONS`; existing `DecisionEngineTest` | **PASS** | All six outcomes exist in the core model. UI labels shorten some names (`MEMORY`, `TRANSLATED`, etc.), but the core decision types are intact. |
| MC-DOCX-002 | §4.1/§4.5 cheap VAD/audio-quality gates run first; silence/noisy/clipped/too-short input stops early | `AudioRecordSource.kt`; `PipelineGates.kt`; `PushToTalkViewModel.kt`; `DecisionEngine.kt` | `T0-STATIC-EARLY-GATES`; existing `AudioRecordSourceTest` | **FAIL / GAP** | The live pre-ASR predicate is only `shouldRunAsr(audio) = audio.speechPresent`. Clipping, low `audioScore`, and short valid-speech duration are evaluated later inside `DecisionEngine`, after ASR has already executed; the live `AudioEvidence` path also leaves `tooNoisy=false` and uses a near-constant non-clipped `audioScore=0.95`. This violates the required VAD/audio-quality-gate → ASR ordering and early-stop semantics. |
| MC-DOCX-003 | §4.2/§4.3 language-routed ASR: PhoWhisper Base for VI, Whisper Small for EN; calibrated reliability guard before MT | `ModelProfile.kt`; `WhisperAsr.kt` (`LanguageRoutedAsr`); `DecisionEngine.kt` | `T0-STATIC-ASR`; existing `ModelProfileTest`, `DecisionEngineTest` | **PARTIAL / GAP** | Model routing is correct, but production `WhisperAsr` always emits `asrScoreCalibrated=false`. Therefore `DecisionEngine.minAsrScore` is never enforced on the production Whisper path. `noSpeechProbability` is still checked, but the DOCX-required calibrated `asrScore + noSpeechProb` guard is incomplete. |
| MC-DOCX-004 | §1.2/§4.1/§4.5 local memory lookup occurs before NMT; only approved/trusted memory may be reused; every reuse must receive a fresh safety check | `DecisionEngine.kt`; `LocalMemoryRetriever`; `RoomLearningStore.kt`; `LearningDb.kt` | `T0-STATIC-MEMORY`; static inspection only for the reuse branch | **PARTIAL / GAP** | Trusted memory reuse is checked against safety only when `sourceFrame.hasSafetySignal` is true. For a low-risk source frame, `reuseOk` is set directly to `true` without extracting/comparing the recalled target, so the required fresh safety re-check is conditional rather than universal. Existing `DecisionEngineTest` has no memory-reuse case and is not evidence for this requirement. |
| MC-DOCX-005 | §4.2/§4.3 NMT is OPUS-MT VI↔EN, INT8, invoked after memory does not yield a valid reuse | `DecisionEngine.kt`; `OnnxTranslator.kt`; `PushToTalkViewModel.kt`; `config/models/dev-vi-en-phase2.json` | `T0-STATIC-NMT`; current `tools/e2e-jvm` OPUS stages | **PASS for component path; not full E2E** | Real OPUS-MT runtime path exists. Current desktop harness exercises OPUS-MT as a stage, not yet through the complete DecisionEngine pipeline. |
| MC-DOCX-006 | §1.2/§4.3 Safety Preservation extracts source/target frames, compares, verifies, performs bounded repair, or refuses with `ASK_REPEAT`; never invent meaning | `DecisionEngine.kt`; `SafetyFrameExtractor.kt`; `SafetyFrameComparator.kt`; `SafetyRepairer.kt` | `T0-STATIC-SAFETY`; existing `DecisionEngineTest`, `SafetyFrameExtractorTest` | **PASS for core ordering** | Safety runs after model output and also re-checks safety-bearing memory reuse. Full neural E2E safety behavior is not yet covered by the desktop harness. |
| MC-DOCX-007 | §4.1 output policy: repaired output is shown but spoken only after explicit confirmation | `PushToTalkViewModel.kt` (`render`, `confirmRepairedAndSpeak`) | `T0-STATIC-REPAIR-CONFIRM` | **PASS** | `REPAIRED_TRANSLATION` does not auto-speak; confirmation triggers TTS. |
| MC-DOCX-008 | §4.2 Piper requirement: deterministic text normalization expands values/units (examples: `15 mm`, `220 V`, `3.5 bar`) **before synthesis** | `PushToTalkViewModel.kt` (`speak`); `PiperTts.kt`; `core/.../normalize/NumberNormalizer.kt` | `T0-STATIC-NORMALIZE` | **FAIL / GAP** | The live speech path passes decision text directly to `PiperTts.synthesize`. `NumberNormalizer` is currently a safety/object-ID extraction helper, not a spoken-text normalizer. No pre-TTS normalization stage matching the DOCX was found. |
| MC-DOCX-009 | §4.2/§4.3 offline Piper TTS + cached safety PCM, followed by local audio playback | `PiperTts.kt`; `PushToTalkViewModel.kt`; `AudioOut.kt` | `T0-STATIC-TTS`; existing `RealPipelineInstrumentedTest` | **PASS on Android path; desktop gap** | Android has real Piper/cached PCM playback. Current `tools/e2e-jvm` explicitly omits `PiperTts`, so Mac CPU full E2E is not yet complete. |
| MC-DOCX-010 | §4.2 selected primary models must remain Silero, PhoWhisper Base VI, Whisper Small EN, OPUS-MT VI↔EN, Piper | `SileroVad.kt`; `ModelProfile.kt`; `config/models/dev-vi-en-phase2.json`; `OnnxTranslator.kt`; `PiperTts.kt` | `T0-STATIC-MODELS`; existing `ModelProfileTest` | **PASS** | Primary Phase-2 selections match `Submission_Final`. Per the locked authority, Whisper Tiny is benchmark-only and is not a product fallback. |
| MC-DOCX-011 | §4.4 one ASR and one MT direction resident per turn; warm-load/warm-up immediately after language/direction selection; two VI/EN Piper voices managed to avoid switch latency | `WhisperAsr.kt` (`LanguageRoutedAsr`); `OnnxTranslator.kt`; `PushToTalkViewModel.kt`; `PiperTts.kt` | `T0-STATIC-RESIDENCY` | **PARTIAL / GAP** | Residency is direction-scoped, but selection currently changes UI state only. The selected ASR is loaded and warmed on first `transcribeDetailed`, MT loads on first `translate`, and Piper creates the voice engine on first synthesis. The required post-selection ASR/MT warm-load is missing, and there is no explicit eager warm-load of both VI/EN Piper voices. |
| MC-DOCX-012 | §4.4/§6 device dtype/backend and S24 memory/latency claims must not be treated as equivalent to Mac CPU measurements; Android should use QNN/NPU when compiled/profiled evidence permits, with CPU fallback | `WhisperAsr.kt` (`LanguageRoutedAsr`); `PiperTts.kt`; `tools/e2e-jvm/src/Main.kt`; run metadata | `T0-HOST-BOUNDARY`; `T0-STATIC-ANDROID-BACKEND` | **PARTIAL / GAP** | Mac CPU evidence remains correctly separated from Galaxy S24/QNN/FP16 claims. Separately, the current Android `LanguageRoutedAsr` hardcodes `WhisperAsr.load(..., preferCpu = true)`, forcing the CPU path even when QNN artifacts are present. That diverges from `Submission_Final` §6, which specifies ORT QNN/NPU where compilation/profile evidence passes with CPU fallback. |
| MC-DOCX-013 | §6 offline-first: model inference, memory and audit stay local; no cloud inference | `PushToTalkViewModel.kt`; ORT runtimes; `RoomLearningStore.kt`; `PiperTts.kt` | `T0-STATIC-OFFLINE` | **PASS by inspected architecture; runtime airplane-mode proof deferred** | Inspected product path uses local model files, local Room/SQLCipher state, ORT/sherpa inference. Task 0 does not claim a network-isolation runtime test. |
| MC-DOCX-014 | Mac adaptation may replace microphone/playback adapters with WAV/files and hardware backend with CPU, while preserving the same state machine and branch order | `tools/e2e-jvm/src/Main.kt`; `WhisperAsr.load(... preferCpu = true)`; Android `DecisionEngine`/output policy as reference | `T0-MAC-ADAPTER-BOUNDARY` | **NOT YET SATISFIED** | Existing desktop harness is stage-oriented and does not yet wire corpus WAV through DecisionEngine, spoken normalization and Piper TTS. Later Tasks must close this without bypassing memory/Safety/normalization. |

## Task-0 acceptance conclusion

Task 0 is **LOCKED_TO_SUBMISSION_FINAL**. The authority recorded in `reports/mac-cpu/20260927-0250-preflight/metadata.txt` is valid and binding for this execution: Whisper Tiny is benchmark-only; deployment scope is compatible Snapdragon Android devices with Galaxy S24 as the reference validation device; and `<1.6 GB` is a device planning target, not a Mac gate.

The lock does not imply implementation conformance. Current gaps that must remain visible are: audio-quality checks do not all precede ASR; the production Whisper score is uncalibrated so the configured ASR-score threshold is bypassed; fresh memory safety re-check is conditional rather than universal; language/direction selection does not perform the required ASR/MT warm-load; Android currently forces the ASR CPU path despite the QNN-first device design; spoken-text normalization before Piper is missing; and the Mac harness is still stage-oriented rather than complete neural E2E. Benchmark success cannot waive any of these gaps.
