# Full audio BTC200 — 2026-11-14

Scope: real frozen WAV → Kotlin VAD/ASR/core safety policy → local FP32 MT/Piper worker. This is VPS functional evidence, not Snapdragon timing.

| MT candidate | rows | runtime failures | audio files verified | policy errors | p50 file-ready ms | p95 file-ready ms | raw MT chrF | spoken subset chrF |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| EnViT5 | 200 | 0 | 200 | 0 | 4370.8 | 8042.4 | 46.540 | 47.259 |
| VinAI v2 | 200 | 0 | 200 | 0 | 4928.9 | 9766.2 | 47.868 | 48.154 |

ASR routing was unchanged for this run: VI PhoWhisper-small WER 3.676% on 100 VI rows; EN Whisper-small normalized WER 10.740% over all 100 rows, 9.786% on 99 emitted transcripts. The references are attached only after inference. TTS WAV output was verified for every row. One VinAI row and one EnViT5 row were no-speech/prompt cases; no translation audio was generated for them.

The timing is file-ready on VPS and includes model/worker overhead. It excludes microphone realtime, Snapdragon execution, AudioTrack and acoustic output. It is not a `<2s` claim.

Safety policy replay: no unsafe autoplay and no extra MT calls were reported. The full app timing clock now starts at PTT release before capture finalization and first-audio status remains unknown until the first AudioTrack write callback.
