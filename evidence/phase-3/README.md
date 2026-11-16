# Phase 3 on-device benchmark

Device: Snapdragon 8 Gen 3 (SM8650) reference phone on Qualcomm Device Cloud, Android 14, 11 GB RAM, run on 16 Nov 2026.

Input: the 200 BTC sample voice clips (100 VI→EN, 100 EN→VI), 16 kHz WAV, fed one by one through the app's own pipeline (`PipelineBenchActivity`): VAD → ASR → clean-up → MT → safety check → TTS → AudioTrack. Models stay loaded between clips (warm). One pass per clip.

Build: an earlier build of the same code as the submitted `voxbridge-43049d3.apk`. Later changes only touch the glossary, quote clean-up of the translation and the demo clip input; none of them changes the translation input for the 200 benchmark sentences, so these numbers still apply. Models: the package's `models/` set.

| | VI→EN | EN→VI |
|---|---|---|
| Clips | 100 | 100 |
| Translated and spoken | 99 | 99 |
| Other outcome | 1 ask-repeat | 1 no speech detected |
| First translated audio ≤ 2 s | 99/100 | 99/100 |
| p50 / p95 / max (ms) | 766 / 1077 / 1354 | 588 / 1003 / 1125 |
| ASR WER | 3.68% | 6.83% |
| chrF (spoken clips) | 49.7 | 51.1 |
| Peak app PSS | 595,821 KiB | 430,976 KiB |

Latency is measured from the end of the input clip (button release) to the first translated audio sample written to the AudioTrack. The clip without speech output counts as a miss in the ≤ 2 s row. WER uses the source transcripts that come with the BTC data. The chrF references are not human-checked: for EN→VI, 21 are human translations and 79 are AI-drafted labels that come with the data; for VI→EN, the team drafted them with an AI model.

## Files

Each run folder holds:

- `results.jsonl`: one row per clip with ASR text, translation, decision and per-stage timings (`asr_ms`, `mt_safety_ms`, `first_write_ms`, ...).
- `score.json`: WER, chrF and latency summary from `tools/` scoring.
- `memory.csv`: total PSS of the app process every 2 s (`dumpsys meminfo`).
- `app-log.txt.gz`: app log lines from logcat.

The VI→EN memory trace stops at the end of the VI→EN clips. The original session went on to try a Kokoro Vietnamese voice that did not load (QAIRT version mismatch); that voice was dropped from the submission and its part of the trace is not included.
