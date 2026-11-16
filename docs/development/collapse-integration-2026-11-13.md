# Integrated translation-collapse gate — all-200 regression

## Full-audio follow-up

A subsequent run now processes all 200 original WAVs per MT model through actual VAD/ASR, MT, production core and Piper output audio. See `full-audio-btc200-2026-11-13.md`; the earlier integration run below remains text-pipeline evidence.

## Change

`DecisionEngine` now calls `TranslationCoverage.isCollapsed(originalTranscript, modelText)` immediately after neural translation and before ordinary/safety output branches. Blank/no-lexical-content outputs and any run of six identical lexical tokens retain the existing `AskRepeat("translation_degenerate")` behavior. The new behavior also rejects dominant repeated token blocks (2–8 tokens, at least three repetitions, covering at least 60% of the output), unless the source itself contains substantial repetition. Source-side repetition protection is heuristic, not proof of meaning preservation.

Only the collapse predicate is wired into production. Missing-action cues, sentence-count/length diagnostics and sentence retry are not called by `DecisionEngine`. There is no extra translation call. The existing safety verification, constrained repair, confirmation/output policy, ASR, memory and TTS paths retain their behavior. Punctuation restoration remains off by default. The shared core is used by the Android app; this run compiles Android but does not build/install a new APK or run QDC.

## Verification

- **164/164 host tests passed locally and on the new VPS**, including a failing-before/passing-after pipeline regression for repeated multiword output, source repetition, punctuation-only output, short/merged but usable output, legacy single-word loops inside long output, one MT call and preservation of safety routing.
- **108/108 phase3 Python tests passed** after adding all-200 replay validation tests.
- Android `:android:app:compileDebugKotlin`: **BUILD SUCCESSFUL**, 48 s. The first offline attempt lacked cached QNN ORT dependencies. The successful run resolved the declared dependency online and used the migrated Sherpa AAR via a local link. Existing deprecation warnings remain; compilation is not an on-device inference or latency result.
- `git diff --check` passed.

## Fresh MT and full before/after decision replay

The manifest includes all 200 BTC texts (100 per direction), without dev/test selection. All **400 MT outputs were freshly generated**, 200 per model, FP32 CPU, beam2, max_new_tokens=192, pinned existing checkpoints. No cached MT hypotheses were substituted for this run. The exact same new outputs were replayed through the before and after real Kotlin `DecisionEngine` with valid synthetic audio/ASR gate evidence and no local memory. This verifies the post-ASR text pipeline, not 200 WAVs, actual mobile ASR, or QDC.

| Model | Complete fresh MT outputs | Collapse blocks before | Collapse blocks after | Changed pipeline decisions |
|---|---:|---:|---:|---:|
| EnViT5 | 200/200 | 0 | 0 | 0 |
| VinAI v2 | 200/200 | 0 | 0 | 0 |

No MT failures, empty outputs or max-token truncations occurred. Each backend returns 200 `ModelTranslation` decisions on this general BTC corpus, before and after. This does not mean all translations are semantically correct. There are no observed actual collapse errors here from which to estimate real-world collapse recall. Existing source/reference text and translation outputs are not edited by the gate.

Diagnostic raw chrF against the unchanged AI-draft references: EnViT5 **52.3154**, VinAI **53.8089**. These scores are unchanged by the gate; they are not percentages of correct translation. References are not human-verified. VPS timing under concurrent workloads is not used to claim the <2 s Snapdragon requirement.

## Controlled pipeline rejection test

The same 240 controlled rows from the previous experiment were sent through both real pipeline versions:

| Rows | N | Collapse blocks before | Collapse blocks after |
|---|---:|---:|---:|
| Full/compact meaning-preserving controls | 80 | 0 | 0 |
| Blank or repeated-phrase errors | 80 | 40 | 80 |
| Missing-proposition / padded omission errors | 80 | 0 | 0 |

Counts here are specifically `translation_degenerate` blocks. Pre-existing safety blocks are separate and are not claimed as new collapse detection. The complete targets/decisions remain available in the raw replay. Controls are synthetic AI-authored examples previously exposed during development, not an independent human acceptance set. In particular, the new gate intentionally does not claim to detect the omitted-content rows.

## Evidence and runtime location

- Local ignored root: `output/phase3/coverage-integration/`.
- VPS root: `/home/opc/voxbridge-mt3-20261113/coverage-integration-20261113` on `<vps-host>`.
- `summary.json`: before/after counts and checksums of the runtime test JARs and manifest.
- `{envit5,vinai}/results.jsonl`, `metadata.json`: all fresh MT outputs and model settings.
- `{envit5,vinai}/pipeline-comparison.jsonl`: exact source, output, before and after decisions.
- `control-pipeline-comparison.jsonl`: controlled failures and false-positive checks through the actual pipeline.
- `host-tests/`, local `host-green/`, `android-compile-online.log`: regression/build evidence.
- `verify_collapse_integration.py`: reproducible ID/revision/settings validation and replay runner.

Source changes are synced to `/home/opc/voxbridge-current`. Nothing is committed or pushed by this task. The teammate's ASR models/code are not modified.
