# Mac CPU benchmark data

The benchmark generator is `tools/mac-cpu-benchmark/prepare_corpus.py`. Large audio stays under
`build/mac-cpu-data/` and is not committed.

Sources are pinned and recorded in `build/mac-cpu-data/provenance.json`:

- Google FLEURS revision `70bb2e84b976b7e960aa89f1c648e09c59f894dd`, locales `vi_vn` and
  `en_us`, CC BY 4.0. FLEURS calls its validation split `dev`; VoxBridge uses that official `dev`
  split for the plan's validation/dev tuning set and reserves `test` for full evaluation.
- DEMAND Zenodo record `1227121`, 16 kHz `STRAFFIC`, `DWASHING`, and `DKITCHEN` channel 1,
  CC BY-SA 3.0. These are environmental-noise recordings, not claimed to be a factory-floor
  field dataset. The provenance file records the SHA-256 of each cached ZIP and extracted
  `ch01.wav`; no DEMAND recording is labeled speech-free without separate verification.

Each generated FLEURS PCM conversion is logged in `build/mac-cpu-data/conversion-log.jsonl`
with source/output SHA-256, PCM16/mono/16 kHz format metadata, and the conversion command.

Tier rules:

- `smoke`: the ten exposed FLEURS test IDs already recorded in `tools/fleurs-audio-regression/`.
- `dev`: 50 official FLEURS `dev` utterances per language, stratified by duration. Clean/noisy
  twins use deterministic DEMAND segments at 20/10/5/0 dB.
- `full`: the complete FLEURS test split for VI and EN. The ten historical smoke IDs remain in
  the manifest with `exposed=true` so reporting can separate them from previously unseen rows.

Every manifest row records the WAV SHA-256. Public speech references are scorer-only and must
never be passed into the inference path. Synthetic safety audio is reported separately from human
FLEURS WER. Generated safety text templates are `provisional-template` until human verification;
semantic groups are assigned to dev/holdout before VI→EN and EN→VI variants are emitted.

The generated `edge` rows include silence, clipping, short audio, low gain, leading/trailing
silence, an exact 30 s boundary, verified synthetic noise-only audio, a corrupt WAV, and an 8 kHz
WAV. The corrupt and wrong-rate rows explicitly set `expected_input_rejection=true` with a reason.
