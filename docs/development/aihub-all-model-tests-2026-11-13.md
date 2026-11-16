# AI Hub model experiment matrix — 2026-11-13

User authorized running all useful remaining AI Hub tests for existing models. Scope: retained EnViT5, VinAI VI→EN and EN→VI, confirmed PhoWhisper-base, current Piper VI/EN, existing VieNeu Nano, and Silero VAD feasibility. No other ASR selection, app model replacement, QDC booking or full-device latency claim.

The primary workflow is source/optimized ONNX → W8A16 quantization using real inputs → QNN DLC compile → NPU profile + paired inference against local ONNX. Existing unquantized QNN graphs receive baseline profile and real-input inference. Tensor names must map explicitly; decoder timing is one step, not an utterance. Runtime/toolchain compatibility and static decoder shapes remain deployment gates.

MT calibration uses actual BTC ASR transcripts (no target references): EnViT5 16 sources across both directions, 8 sources per VinAI direction, plus separate 4/2-source graph probes respectively. These are exposed diagnostic samples, not independent task-quality tests. Greedy-generated prefixes create actual hidden/KV states for the fixed decoder past8 probe; EOS-padding is recorded. Calibration is small and not a completed full200 translation-quality assessment. Source128/batch1 graph limitation remains.

PhoWhisper reuses exact earlier custom source graph contract, successful jobs `jp0j2300g`, `jp8xm0yqg`. Optimized ONNX exports retain output_0… names; map to saved original encoder/decoder semantic IO names using the previous artifact manifest, not guesses. Ten original VI BTC WAVs supply mel features, 8 calibration/2 graph probes, decoder states at steps3/8. No tokenizer/reference transcript used to steer recognition beyond the existing task/language prefix. No ASR model selection changed.

Nano inputs are captured from actual SDK3.8.3 Trúc Ly inference with cfg3,8steps,sway-1, seeds42–44; separate capture shapes and intermediate stages retained. One static shape per four graphs is compiled. This does not implement arbitrary-length TTS or validate full generated speech on NPU. Piper uses actual piper-phonemize1.1.0 phoneme IDs for one short phrase per voice; compile/inference/profile smoke only, insufficient calibration diversity to claim production quality. Missing symbols recorded. Silero uses real512-sample/16kHz WAV frames with recurrent states; keep its original conditional graph when local specialization triggers an incompatible inactive8kHz branch.

Resolved export/input blockers:
- Piper ONNX repeats identical metadata keys: remove only identical duplicates; conflicting keys fail. Weight/compute graph not rewritten for this fix.
- Nano text/Piper/VAD QNN require `--truncate_64bit_io`; original failed IDs retained, corrected jobs submitted. Input integer values fit32-bit. QNN probe input dtype must match resulting32-bit interface; original ONNX calibration/ref retains64-bit.
- VAD specialization failed local ORT shape inference in a conditional LSTM branch; preserve original graph and pass static shapes to compiler rather than modifying internal branch computation.

Raw job IDs/results are stored under VPS `/home/opc/voxbridge-mt3-20261113/aihub-*` and copied into ignored local `output/aihub-selected-20261113/all-tests/`. Credentials read from existing local config and streamed through SSH stdin into client memory, never saved on VPS. Job ledger records intent before submission and prevents blind retries of ambiguous failures. No credentials in job/config/docs/source files.

Initial baseline profiles on Galaxy S24 Ultra: EnViT5 encoder2.975ms, decoder-init5.659ms, decoder-step5.099ms; VinAI VI→EN5.242/9.594/8.770ms. Profile execution-detail reports all listed nodes NPU for those graphs. Synthetic profile inputs, single decoder step; these values are not full decoding latency, beam2, phone app E2E or<2s proof. Updated exact timing/memory is in raw profiles, not this initial snapshot.

## Extended experiments and diagnosed failures

Added W8A8 speed/accuracy probes for the three MT encoders, PhoWhisper encoder and Nano vector estimator. Existing baseline inference jobs are reused. W8A16 remains the main full graph matrix; W8A8 is not silently mixed into production.

Added linked-context experiments for baseline/W8A16 encoder+decoder groups of EnViT5, each VinAI direction and PhoWhisper-base. Baseline links succeeded; initial W8A16 MT links failed with exit15. Separate W8A16 DLC graphs remain usable candidates; failed linking does not mean quantization/individual compilation failed. Linking/profile artifacts are graph-level only.

Piper corrected QNN conversion fails by signal11; logs report dynamic-shape/ConstantOfShape support problems. Silero conversion fails shape inference through If/LSTM. Keep these on CPU; don't blindly resubmit identical graphs. Raw compiler logs saved locally under `output/aihub-selected-20261113/all-tests/compile-errors/`.

Quality-tool correction: AI Hub often exposes output_0…output_N names and unordered dictionary keys. Never pair tensors using dictionary insertion order. `graph_validation.align_outputs` requires exact semantic-name coverage or contiguous numbered-name coverage and maps index explicitly; missing/unrecognized names fail. Three tests reproduced the broken-order behavior before the fix and passed after. Earlier mapping exceptions are tooling errors, not model quality findings. Workers are resumed only after safe idle stops, preserving submitted IDs and avoiding duplication.

Workers support ledger resumption via `start_remote_tests.py`; no token files on VPS. Model artifacts are downloaded there with byte sizes, SHA256 and runtime metadata. Pending cloud work is not reported as passing; file/runtime compatibility and full utterance quality remain distinct gates.

## Critical parity finding: EnViT5 encoder

Real-input device inference found a major discrepancy in the unquantized EnViT5 encoder NPU output. Four probes, non-padding tokens only: cosine0.216–0.271 against original Torch/ONNX. Original ONNX exactly matches Torch for these inputs; AI Hub optimized ONNX CPU remains cosine≈1.0, max abs0.0004–0.00094. The discrepancy therefore appears downstream of optimized ONNX in QNN/NPU execution, not in the original export or a padding-only metric. Do not promote the fast baseline encoder on compile/profile success alone. Decoder-init/step probes with original hidden states preserved top tokens4/4, but that is not chained encoder→decoder correctness.

VinAI VI→EN encoder non-padding output cosine0.999956–0.999971 on two probes; padded positions differ more. This remains graph-level evidence, not whole-translation quality. Saved targeted diagnosis: VPS `aihub-envit5-debug/result.json` and `aihub-all-tests/encoder-parity-detail.json`.

Additional CPU/GPU profile+inference jobs for the same EnViT5 encoder have been submitted to isolate backend behavior and evaluate a possible fallback, while existing W8A16/W8A8 probes continue. No promotion until paired output validation.

English ASR existing `openai/whisper-small@973afd24965f72e36ca33b3055d56a652f456b4d` compile jobs `jp1jw2l7p`, `jgd3qn9zp` were rechecked SUCCESS. Existing Whisper Small profile evidence remains separately recorded; no ASR model selection changed or redundant alternative checkpoint downloaded.

## Mask-sentinel diagnostic submission

EnViT5 original attention-mask constant `-3.4028234663852886e+38` is not FP16-representable. A diagnostic-only clone changes that one scalar to-10000. CPU ONNX output parity on all20 calibration+probe samples is exact (max abs0). Submitted `j5wrdq9zp`; NPU inference/quantization/profile follow automatically after successful compile. This tests a plausible root cause; it is not accepted as a fix before NPU validation. Original artifacts remain intact. CPU/GPU fallback diagnosis on the original graph also remains queued.

Snapshot during work:157 follow-up/experiment jobs recorded by the collector,88 terminal,79 successful; this includes intermediate stages, explicit retries and failures, not157 separate model configurations. Raw IDs/statuses are the source of truth; snapshot will become stale as cloud work continues. Baseline PhoWhisper profile32.453ms encoder/3.511ms decoder step on S24 Ultra, estimated memory216.7/118.4MiB respectively. These remain per-graph profiles and do not establish E2E recognition latency.

## Latest saved snapshot and running continuation

Local `output/aihub-selected-20261113/all-tests/vps-snapshot/` stores job ledgers, raw profiles/logs, input manifests, detailed parity diagnosis and downloaded model size/hash metadata. Latest snapshot157 collected jobs,90 terminal,81 successes; downstream jobs can increase the total. No comparison-tool exceptions remain in that snapshot: nine MT graph comparisons, three Nano comparisons and four reused baseline comparisons in W8A8 probes completed. Numbers do not imply acceptance; encoder discrepancy above still blocks promotion.

VPS coordinators were safely resumed from idle checkpoints with submitted IDs preserved. MT, Nano, Pho, corrected IO, W8A8, diagnostic mask, linking, profile collection and artifact downloads continue. Each scan waits45–60s. The same submitted experiment runs rather than a recurring automation/remote agent; no API token persisted. CPU/GPU diagnosis may still be pending. No completed/quality-pass claim is made for queued stages.
