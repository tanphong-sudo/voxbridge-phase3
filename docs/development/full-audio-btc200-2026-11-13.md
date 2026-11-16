# Full audio pipeline on VPS, 200 BTC WAVs per MT backend

## Completed run

The user's requested pre-QDC functional run is complete: **200 original WAVs × EnViT5 and VinAI**, with actual VAD, actual ASR, actual MT, real production decisions/safety/output policy and actual synthesized output audio. All WAVs were validated against the original SHA-256 and PCM16 mono 16 kHz headers. The original 200 files total **929.806 s (15 min 30 s)**.

Execution:

`WAV → production Kotlin Silero/CaptureAnalysis → production LanguageRoutedAsr → local selected HF MT → production DecisionEngine (collapse + safety) → SpeechOutputPolicy → spoken normalization → Piper audio WAV`.

VI ASR is the currently configured **PhoWhisper-base**, EN ASR **Whisper-small**, both existing ONNX exports and unchanged teammate decode/config. MT is the retained pinned FP32 beam2 model, selected per run. VAD/ASR/core is the real Kotlin code; MT is local Python Transformers and TTS is Python sherpa-onnx **1.13.3**, using the same Piper voices, noiseScale=0, noiseScaleW=0, lengthScale=1 and two TTS CPU threads as Android. Rejected cases receive the existing cached prompt in the source language. Repaired translations would wait for confirmation; no confirmation was fabricated.

This is functional full-stage execution on Linux, **not Android runtime parity**, a microphone test, physical speaker playback, an acoustic intelligibility review, or a measurement of end-of-speech→first audio on Snapdragon. Entire output WAV readiness is the measured endpoint; it is not a first-sample callback.

## Results

| Metric | EnViT5 | VinAI v2 |
|---|---:|---:|
| Cases completed | 200/200 | 200/200 |
| Runtime failures | 0 | 0 |
| Translation audio outputs | 197 | 198 |
| Repeat/no-speech prompt outputs | 3 | 2 |
| Validated output WAVs + SHA256 | 200 | 200 |
| Output-policy violations | 0 | 0 |
| Raw MT chrF over all 200, missing MT counted empty | 46.15 | 46.94 |
| Text-only chrF on reference transcripts, contextual baseline | 52.32 | 53.81 |
| Sum of case processing time on VPS | 508.13 s (~8m28s) | 728.52 s (~12m09s) |
| VPS file-ready→complete WAV-ready p50 | 2346 ms | 3132 ms |
| VPS file-ready→complete WAV-ready p95 | 4947 ms | 7360 ms |

Runs executed concurrently while unrelated VPS work was active. Case totals exclude initial worker startup and physical input/output replay. Cold loads inside cases are included; Python IPC, full TTS generation and WAV writing are included. These are not clean comparative device benchmarks, nor evidence for the <2 s competition target.

Each run produced 199 ASR outputs; all 199 stopped at EOT, with no observed token-cap stops. ASR texts and the no-speech result are **identical across all 200 IDs between runs**.

ASR diagnostic errors against supplied source transcripts:

- VI: WER 7.48%, CER 3.88%, all 100 cases. Unicode lowercase/punctuation normalization, without Vietnamese word-number equivalence.
- EN: WER 9.79% on 99 emitted transcripts with Whisper EnglishTextNormalizer. Including the VAD-rejected file as an empty hypothesis, **10.74% WER over all 100**. Conditional error is not presented as full-set coverage.

References remain frozen AI translation drafts (`human_verified=false`). chrF is reference similarity, not a percentage of correct translation. 197/198 spoken translations are not 197/198 correct translations. Output WAV checks verify nonempty format/rate/frame count/hash, not that every utterance was heard or pronounced correctly. No full listening review is claimed.

## Cases requiring attention

1. **VE-2920, both models:** source “Quầy bên phải sẽ in thẻ cho anh.” ASR becomes “ngoài bên phải sẽ in thẻ tràn.” The resulting lexical spill/hazard signal triggers `AskRepeat(safety_slot_lost:LOST_HAZARD)`. The original source is an ordinary instruction; this is an unwanted rejection arising from corrupted ASR plus a lexical safety match, not proof that the original utterance was unsafe.
2. **VE-8787, EnViT5:** ASR corrupts “Không hủy…” into “không hỉ…”. MT collapses into “No, no, no, no, no, no.” The integrated collapse guard blocks translation audio and emits the ask-repeat prompt. VinAI does not collapse on this sample. This particular six-identical-token pattern was already covered by the legacy guard; do not attribute this example uniquely to the new multiword-loop enhancement.
3. **EV-2748, both:** VAD finds zero speech. The supplied original WAV is 5.96 s, peak PCM amplitude 244/32768 and RMS 6.94/32768, roughly -73.5 dBFS RMS. It is nonzero but extremely quiet. The run correctly follows current no-speech policy and emits the cached prompt; this does not establish that the underlying recording contains no intelligible speech. No automatic gain, threshold change or audio replacement was introduced.
4. **EV-0157, illustrative ASR error:** “one thousand nine hundred and ninety” becomes “1900 and 90”. Downstream MT/TTS render that incorrect recognized year. A functioning pipeline does not repair all semantic ASR errors.

## Implementation and verification

New host harness `tools/e2e-jvm/src/FullAudioRunner.kt` uses production source and adds a persistent local MT/TTS worker (`audio_pipeline_worker.py`). It starts from original WAVs, never uses transcript/reference text to drive ASR or primary MT, and attaches references only after the terminal runtime decision/audio output. No MT input comes from the reference transcript. Runtime outputs are not replaced by cached text results.

The 200-case manifest is frozen by SHA256. Every source audio SHA is checked before inference. MT runs use the existing fixed revisions and beam2/max_new_tokens=192; a cap triggers an explicit recorded failure. Worker errors are surfaced per case. Python TTS converts samples to PCM16 with the same scaling as app; fixed prompt IDs prevent arbitrary cache paths. General TTS runs SpokenTextNormalizer in Kotlin immediately before synthesis. No second-pass translation, punctuation restoration, safety bypass or auto-confirmation is enabled.

JVM real-runtime compilation succeeded. **113 phase3 Python tests passed** after worker and scorer tests were added; the integrated core's previously verified 164 host tests and Android compile remain valid (no ASR/core behavior changes here). The harness is not yet an Android adapter for VinAI/EnViT5.

## Before spending QDC time

The functional WAV-to-WAV baseline now exists. Preparation still required:

1. Freeze the exact teammate-selected ASR artifacts. A catalog `openai/whisper-small` is not a replacement for a PhoWhisper checkpoint simply because both are Whisper-family models.
2. Export/adapt the selected MT backends and build an Android tokenizer/encoder/decoder/KV/beam2 adapter. The current Android translator is still ML Kit/Marian-oriented; these VPS HF workers are not phone inference code.
3. Establish CPU-export numerical/text parity before quantization. Calibrate/quantize and compile for the actual target SoC/runtime through AI Hub; preserve references and compare outputs after conversion. Do not assume compilation alone compresses weights or preserves quality.
4. Match runtime versions: the existing catalog Whisper-small SM8650 profile declares ORT 1.30.0 / QAIRT 2.50 while the currently built Android QNN artifact is ORT 1.20.0. Its `android_runtime_validated` flag is false. This is an unresolved compatibility gate, not a working compiled ASR deployment.
5. AI Hub model-level profile/inference smoke, then download the compiled artifact and stage it with tokenizers/metadata/notices/checksums. VAD/core/output policy need not all be sent to NPU.
6. Build/install the actual APK and run 10 original WAVs through the complete on-device pipeline first; verify NPU placement rather than silent CPU fallback, memory and output quality. Only then run all 200 with both MT configurations using the same ASR/VAD/TTS settings.
7. Separate file-fed correctness benchmarking from actual mic/release→first-speaker-audio latency and warm/cold/direction-switch memory testing.

The 200 audio files contain 15m30s of speech. A direct file-fed device run need not wait that long for input playback; a realtime playback/acoustic test must. Plan device-session duration from a 10-case pilot, loading/install time and whether full output audio is played, rather than 200 × a 2 s inference target. No QDC booking, cloud compile job or credential change was made by this task.

## Evidence

Local ignored root: `output/phase3/full-audio-btc200/`.
VPS: `/home/opc/voxbridge-mt3-20261113/full-audio-btc200` on `<vps-host>`.

- `summary.json`, `manifest200.jsonl`, `FREEZE.json`.
- `{envit5,vinai}/results.jsonl`: original source hash, VAD evidence, raw ASR and diagnostics, raw MT, final decisions/text, TTS metadata, stage timing and reference metadata.
- `{envit5,vinai}/audio/*.wav`: actual translation or prompt audio, with SHA256 in each result.
- `comparison.html`: searchable per-case source/ASR/MT/decision/audio view.
- VPS `smoke-envit5/`: two-direction preliminary end-to-end smoke.
