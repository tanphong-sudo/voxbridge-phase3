# FLEURS voiced-audio regression

This is the real-audio companion to `tools/fleurs-regression`, which scans public FLEURS
transcripts for safety-layer false activations.

The JVM E2E harness accepts a tab-separated corpus through `VOXBRIDGE_ASR_CORPUS`:

```text
<vi|en>    <absolute-or-manifest-relative PCM16 WAV path>    <reference transcript>
```

Every WAV must be mono, 16 kHz, PCM16. Google FLEURS test audio is distributed as mono 16 kHz
IEEE-float WAV, so convert it losslessly in sample rate/channel count before the harness:

```bash
ffmpeg -i fleurs.wav -ar 16000 -ac 1 -c:a pcm_s16le fixture.wav
VOXBRIDGE_ASR_CORPUS=/path/to/corpus.tsv bash tools/e2e-jvm/run.sh models
```

The harness runs the real Kotlin Silero VAD, language-routes VI to PhoWhisper Base and EN to
Whisper Small, computes WER independently from the ASR code, and fails on runtime/blank-output or
catastrophic aggregate WER (>=60%). The 60% threshold is only a regression tripwire; it is not a
quality acceptance target and does not replace the full FLEURS evaluation in the proposal.

For the 0 dB noise check, use the official DEMAND dataset from Zenodo record `1227121`. The
2026-09-27 run used `STRAFFIC_16k.zip`, channel `STRAFFIC/ch01.wav`. Noise was sliced to the speech
duration, scaled so RMS(noise) == RMS(speech), mixed sample-by-sample, and both components were
subject to the same common anti-clipping gain if necessary. This preserves exactly 0 dB SNR.

The exact ten FLEURS test utterance IDs used by the recorded run are in `sample-ids.tsv`.
