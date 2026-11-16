# Work before the next QDC session — 2026-11-14

Do not request a new device session just to repeat successful standalone compile tests. The next useful session must exercise the chosen runtime in the actual pipeline and measure end-of-speech/release → first audible output, with the normal safety/confirmation policy.

## Preserved QDC results after cache optimization

All model quality runs were on SM8650 using exact QNN FP16 contexts, no CPU fallback. The key runtime change is pinned double-buffered ORT outputs plus reusable direct **ByteBuffer** cache gathers. The earlier FloatBuffer gather attempt did not help: its per-element copies dominated cache reorder. Byte-buffer bulk copying removed that bottleneck.

| Candidate | Quality on unchanged corpus | Full phone decode p50 / p95 |
|---|---|---|
| PhoWhisper-small,100VI WAVs | WER3.676%,100/100 text identical to FP32 |711 /1024ms|
| EnViT5,200sentences,stable RMS encoder |chrF51.628,one existing192token cap |723 /1297ms|
| VinAI VI→EN,100sentences |chrF57.121|883 /1189ms|
| VinAI EN→VI,100sentences |chrF49.358|1250 /1926ms|

EnViT5 output is200/200 identical to the pre-buffer-reuse QNN candidate and ASR100/100. VinAI output changes between QNN runs/layouts (88/100 VI→EN and69/100 EN→VI exact agreement with the earlier QNN run); do not claim the cache patch is bit-identical for VinAI. CPU beam2 implementation parity was independently checked on all400 baseline full translations. The QNN numeric/layout sensitivity still needs investigation before final candidate selection.

These are standalone measured decode times. Do not sum quantiles and label the result observed end-to-end latency. The integrated app has not yet demonstrated<2s.

## TTS now measured

Piper CPU via the real Sherpa Android runtime was measured on100VI+100EN first sentences of BTC reference translations, warm voice, zero noise. No playback/acoustic timestamp was captured; `first_callback_ms` is audio availability only. These are not the final app's spoken normalization/chunk plans.

| Threads | VI callback p50 /p95 | EN callback p50 /p95 |
|---|---|---|
|2|795 /1662ms|593 /1188ms|
|3|577 /1397ms|473 /923ms|
|4|553 /1173ms|404 /763ms|

All sample counts matched across3vs4threads; PCM hashes matched45/100VI and81/100EN. Four threads were faster on81/100VI and87/100EN cases. This is sequential same-device evidence, not a randomized paired acoustic-quality trial. Product default remains configurable3threads until waveform-difference/intelligibility checks justify changing it.

Piper QNN retry succeeded after separating the dynamic frontend from a static64-frame vocoder and correcting output rank. Both VI/EN vocoders compiled under QAIRT2.51 (AI Hub) and2.50 (Android runtime), ran on AI Hub and the QDC QRD. QDC graph p50~7.50ms/block, output16384samples; SNR vs CPU31.30dBVI/32.48dBEN, max absolute waveform difference0.00552/0.00687. **This is not full TTS latency.** Dynamic phoneme/duration/flow handling, overlapping vocoder chunks, output trimming, normalized-text parity and listening checks remain required. A proposed Sherpa frontend-to-latent transport currently fails full-waveform shape parity; keep it a rejected probe, not a shipped TTS backend.

## Safety implemented offline

See `safety-logic-hardening-2026-11-14.md`:196host tests,51synthetic safety decisions, recorded400translation replay. The repeated long EnViT5 output is now rejected by the existing production collapse path; repair still requires explicit confirmation before TTS.

Residual guard limits remain: single dominant action/object/hazard slots, imperfect scope for compound negation and multi-object commands, incomplete vocabulary, numeric word forms/unit conversions. The parser cannot recover safety information lost in ASR. These limits must not be described as100%safety assurance.

## Conditions for opening the next QDC session

1. Wire selected static QNN MT/tokenizer/beam runtime into an app or complete offline pipeline runner; the product app still contains the ML Kit translation path.
2. Keep optimized pinned buffers as the tested path and verify lifecycle/ownership under sequential encoder/decoder execution; preserve source/target quality scores and explicit decoder caps.
3. Choose and validate the TTS path: measured Piper CPU baseline is usable; hybrid QNN remains experimental until complete-waveform and first-audio checks pass.
4. Prepare an offline package containing models/tokenizers/test inputs, integrity hashes, resumeable results, and a single command for200audio pipeline tests.
5. Include on-device capture-release, audio preprocessing, ASR, MT, safety, TTS first PCM and first speaker-write timestamps. Treat speaker-write as a proxy unless acoustic output is measured.

No new QDC session is ready to request solely from the standalone results. All further work above can proceed locally/VPS first; request device time when the package can answer the actual user-latency question.

## Evidence locations

Local: `output/qdc-bench/optimized/` and `output/phase3/safety-logic-20261114/`.
VPS: `/home/opc/voxbridge-mt3-20261113/qdc-bench/`, with original/final candidates preserved.

No competition APK switch to these QNN MT candidates is claimed completed. No model source checkpoint was deleted in this pass.
