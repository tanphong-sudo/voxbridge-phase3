# TTS latency optimization — 2026-11-13

Keep the current Piper VI/EN voices, rate, zero-noise settings and speech speed1. User found their sound acceptable and requested reducing TTS's share of the whole pipeline<2s target. No ASR, MT, safety decision or speech-authorization changes.

## Production changes

- `PiperTts.preload(target)` loads and warms the selected output voice once before capture is enabled. Warm-up PCM is discarded and never sent to playback. Repeated preload reuses the warmed engine; language change frees the previous voice and resets warm state; failures are not marked warmed. No second resident voice or additional model weights.
- Startup now calls preload before returning initialized runners. Direction change prepares ASR then target TTS on the existing serial runner dispatcher and keeps capture blocked until both finish. Logs distinguish ASR/TTS preparation. Setup/reconfiguration time is real and should be reported separately; it is not eliminated compute.
- Provisional Piper thread default2→3 after a paired all395-output VPS experiment. Constructor remains configurable for target-device2/3 comparison; server results do not prove phone gains. No speech-rate increase or quantization. Existing sentence-first playback and exact safety-command cache remain.

## Paired all395 first-chunk experiment

Inputs: actual Kotlin-generated normalized first chunks from197 EnViT5/198 VinAI accepted BTC200 translations, same395 as earlier. Two resident test engines per language only for paired measurement; production retains one. Randomized2/3-thread order per input, warmup excluded, same Sherpa1.13.3 CPU engine/voices, ORT environment, model bytes. Synthetic profile numbers are not substituted for waveform synthesis.

| Translation outputs | N | 2threads p50/p95 ms | 3threads p50/p95 ms |
|---|---:|---:|---:|
| EnViT5 → EN |98|94.8 /170.3|80.6 /137.3|
| EnViT5 → VI |99|191.8 /408.7|169.4 /353.8|
| VinAI → EN |99|104.7 /182.7|93.1 /175.3|
| VinAI → VI |99|186.0 /412.0|161.5 /343.7|

Median reduction11–15%; p95 reduction4–19%. All395 audio lengths identical.193/395 outputs bit-identical PCM; remaining waveforms differ numerically with max sample absolute difference6.66e-5 (~2.18 PCM16 levels before truncation). This is evidence of very small numerical variation, not a new human listening score. No text/negation/numbers rewritten. Thread scheduling/cache effects and unrelated busy-VPS workload limit transferability.

Separate24-representative-first-chunk×2-repeat exploration checked1/2/3threads and max_num_sentences1/4 with randomized per-input order. One thread was slower. Batch4 gains were inconsistent across VI/EN, so keep existing sentence batching; do not mix settings to select a flattering metric. Raw results retained.

## Cold setup moved outside the speech path

Same machine, current two-thread configuration, four repetitions per language using “Dừng máy số năm, không phải máy số ba.” and “Stop machine five, not machine three.”:

| Language | Old cold load+synthesis p50 ms | New preparation p50 ms | Subsequent synthesis p50 ms |
|---|---:|---:|---:|
| VI |767.8|644.2|134.3|
| EN |765.1|637.6|151.6|

Cold and prepared renders had identical PCM within each thread configuration. Preparation includes constructing the engine and synthesizing a discarded short greeting. This small experiment measures direct native-engine behavior, not Android startup/microphone/speaker. No claim that startup or language switching becomes faster. The prepared turn avoids reloading before speech; if load/preload fails, existing explicit error/retry behavior remains. A separate three-thread check is retained as an additional configuration measurement.

## Verification and limits

-170/170 host tests: new native-boundary lifecycle/warmup/retry tests, actual extracted ViewModel direction-prepare method held behind a latch to prove capture stays blocked, existing confirmation/replay/cancel/chunk-order tests. New tests failed with no-op preload and with TTS warmup removed from direction preparation, then passed with production implementation restored.
-Host tests use a fake JNI engine only at the native boundary. Actual Python/Sherpa waveform benchmarks use real voices. Android compile uses the real Sherpa AAR, not the fake.
-Actual Android Kotlin compilation successful; final log saved with artifacts. No phone/QDC measurement and no APK install in this change.

Artifacts: ignored `output/phase3/tts-latency-optimization/` (preload measurements, all395 raw paired rows, exploratory configuration summary, tests/build logs). Scripts: `tts_preload_benchmark.py`, `tts_thread_btc200.py`, `tts_cpu_options.py`.

Next target-device checks: setup time and memory, first prepared turn vs repeated turns, switching directions,2/3CPU threads while the complete pipeline is loaded, actual acoustic onset versus AudioTrack write proxy. Keep the<2s goal tied to end-of-speech→first audible audio for the complete pipeline; these VPS synth timings cannot certify it.

Final three-thread same-phrase check (4repetitions/language): VI cold load+synthesis p50/p95 672.4/780.5ms, preparation595.1/604.7ms, prepared synthesis85.5/109.1ms; EN686.3/728.4ms, preparation608.0/628.0ms, prepared119.7/136.7ms. PCM cold/prepared identical within this configuration. Final Android compile BUILD SUCCESSFUL3s;170 host tests passed after the configurable three-thread default. These short-phrase numbers are separate from all395 variable-length results above.
