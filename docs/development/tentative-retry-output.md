# Tentative output while asking for a repeat

The debug prototype keeps `ASK_REPEAT` as the terminal decision and shows a separate red
`!` card with the best available ASR source and, when available, an MT candidate. The repeat
prompt remains visible and is the only speech emitted. The draft has no replay, approval,
or learning action. It never occupies the accepted `UiState.output` field.

## Configuration

`DecisionConfig.tentativeOutputEnabled` defaults to false for existing core/benchmark callers.
Android debug builds enable it through `BuildConfig.RETRY_DRAFTS`; disable with
`-PvoxbridgeRetryDrafts=false`. Non-debug variants force the flag off. No new models,
services, dependencies, or network requests were introduced.

## Flow

- For a speech-bearing capture rejected before ASR, run at most one existing ASR pass and
  one MT attempt, on the existing inference dispatcher. Empty/silent captures, capture-limit rejections, and PCM longer
  than the 30-second capture bound do not enter this salvage path.
- For an ASR rejection, translate the available transcript as a tentative candidate. For
  a rejection after MT/safety, reuse the existing MT result rather than translating again.
- Keep the original rejection reason. A fluent candidate cannot turn a rejection into an
  accepted translation. The salvage path does not query memory.
- If MT fails, show only the source obtained during this turn. If ASR fails, there may be
  no draft. Cancellation propagates. Starting another capture, changing direction, or
  rendering a non-repeat decision clears the prior draft.
- Placeholder normalization applies only to explicit unknown markers supplied by the
  transcript. The current ASR interface has no per-word confidence/alignment; therefore the
  entire candidate is uncertain. The implementation cannot reliably locate omitted words,
  fill missing negation/numbers, or claim calibrated word confidence.

The existing model provides the best available candidate; this feature does not introduce
a language-model sentence-completion pass or a second ASR model. It does not use reference
transcripts or alter raw ASR evidence. Existing benchmark final-output scoring still treats
`ASK_REPEAT` as rejected; any draft-quality metric must be reported separately. No WER or
translation-score improvement is claimed without a corpus evaluation.

## Validation

Tests cover retained rejection reasons, one-pass limits, empty/silent/oversized input,
disabled configuration, translation fallback, cancellation, explicit placeholders,
post-MT reuse, speech-output policy, UI warning/mapping, and clearing stale drafts.
The host suite exercises core and pure Android presentation helpers; the Android debug CPU
unit/build tasks compile the actual ViewModel and Compose integration. Device microphone,
model inference, accessibility rendering, and end-to-end draft quality require a device.

Validated locally on 2026-11-15:

- Host suite: 221/221 passing, including production rejection-publication/render adapter tests
  for a pending repeat prompt, stale capture generations, and draft clearing.
- Android `testDebugCpuUnitTest`: 62/62 passing; `assembleDebugCpu` and native-library
  verification passed. APK: `android/app/build/outputs/apk/debugCpu/app-debugCpu.apk`.
- JaCoCo on the selected core decision/config/draft/speech-policy and pure draft-presentation
  classes: 98.88% lines, 86.02% branches. This is scoped coverage, not whole-app coverage.
  Evidence: `build/retry-drafts/coverage/summary.json` (ignored local build output).
- Android was compiled offline using local JBR 21 with JVM 17 bytecode via an ignored init
  script; no project toolchain requirement was changed. No device/emulator was connected.

## Real BTC200 run on the existing VPS

On 2026-11-15 the feature was merged into an isolated snapshot of the newer VPS core,
preserving its punctuation option, translation token-limit/coverage checks, added-safety
detection, and memory safety checks. It reused the VPS PhoWhisper-small, native CPU NeMo
Parakeet v2, EnViT5 beam-2 worker, and Piper VI/EN voices/cached prompts. No models were
downloaded, exported, or replaced. This is a CPU functional run, without QNN parity or
device microphone/speaker/latency evidence.

The frozen 200 original WAVs completed on `research-vcn` in 23m57s: 198 translations,
one `AskRepeat`, and one `NoSpeech`; zero runtime failures. All 200 output WAVs passed
hash/header checks on the VPS and after retrieval. The full manifest and rejected cases
remain in all200 denominators. Raw lexical WER is 6.73% overall, 3.68% VI, and 8.07% EN;
normalized EN WER is 7.07% across all100 EN inputs.

Final MT chrF across all200 is 47.81, and the separate display-candidate chrF is also
47.81. Target references are existing unreviewed AI drafts, not human acceptance or an
official competition score. `VE-8787` retains an ASR source draft but no usable MT draft:
the MT loops at its token cap, so only the repeat prompt is synthesized. Drafts therefore
did not increase MT score in this run. `EV-2748` is the retained VAD NoSpeech case.
`EV-0523` still misrecognizes the opening as “Sophia” and is accepted: the current guard
does not catch every semantic ASR error.

The historical VPS baseline uses English Whisper Small. Its normalized EN WER10.74%
and all200 final chrF46.79 cannot be compared as a guard-only intervention: ASR backend,
date/source snapshot and host load differ.

Run source/results on VPS: `/home/opc/voxbridge-experiments/retry-drafts-20261115/`.
Local evidence: `output/btc-retry-20261115/vps-results/full-envit5/score/` contains the
Vietnamese report, summary JSON, and all200 CSV rows. The parent directory preserves
the actual WAVs, input manifest, raw ASR/MT, draft fields, worker provenance, and process
records. `output/btc-retry-20261115/VPS-RUN.md` records integration and validation details.
