# Mac CPU pipeline benchmark

Use a model cache matching `config/models/dev-vi-en-phase2.json`. The runner checks every
selected file and records hashes before inference. Run from the repository root:

```bash
bash tools/mac-cpu-benchmark/run.sh --tier smoke --models /absolute/path/to/models --offline
bash tools/mac-cpu-benchmark/run.sh --tier dev --models /absolute/path/to/models --offline
```

`dev` reads `build/mac-cpu-data/manifests/dev.jsonl`; create the local FLEURS/audio corpus with
`prepare_corpus.py` as described in `testdata/mac-cpu/README.md`. Results live under
`reports/mac-cpu/<run-id>/`: metadata and model hashes, `results.jsonl`, `score-summary.json`,
and `failures.jsonl`. Run `evaluate_run.py` against results and `gates.json` for latency, RTF,
stage checks, and quality checks. Its overall result requires a separately labeled safety score.
The source safety templates are in `testdata/mac-cpu/safety-corpus.jsonl`.

The `full` tier is **not implemented** and exits with an error. Current Task 6 evidence and
open safety failures are reported in `docs/testing/mac-cpu-task6-report.md`; a dev score alone
cannot certify the safety gate. The benchmark runs on Mac CPU with file input. It does not
measure Android mic/speaker behavior, Piper Android JNI, or Qualcomm QNN.

Scorer evidence update (2026-10-15): `--failures` now writes actual JSONL (one object per
failed sample, an empty file for no failures). Safety oracle mismatches and output-policy
violations are included, alongside runner failures. The scorer exits 1 for missing results
or recorded failures. Historical `failures.jsonl` files may contain a pretty JSON array and
omit oracle safety failures; preserve them and re-score into a new output directory.
Reject/repair speech policy checks apply even without safety labels. A zero scorer exit on
an unlabeled corpus does not establish that the labeled safety acceptance gate passed.
Duplicate/empty sample IDs, result IDs outside the manifest, unsupported row types and
malformed output-policy fields are rejected before aggregation (CLI exit 2). This avoids
silently overwriting repeated results or changing denominators. Summary rows remain allowed.

Safety urgency normalization (2026-10-15): boolean labels `true/false` correspond to the
runtime frame values `HIGH/NORMAL`. The scorer applies this mapping only to urgency,
including the pre-repair comparison. Missing, unknown and numeric values do not match.
Re-score old logs to a new directory: this fixes metric accounting, not ASR/MT behavior.
`unsafe_spoken_output` remains the historical field name for an unsafe **speech permission**
against labeled slots; it does not prove audio was actually emitted or heard.

For a small listening comparison with the existing Vietnamese Piper weights:

```bash
python3 tools/mac-cpu-benchmark/audition_tts.py --models models --out-dir output/tts-audition-new
```

Use a Python environment containing `sherpa-onnx==1.13.3`. This renders two identical
texts with production zero-noise settings and the voice's own defaults, recording
hashes, synthesis time, RTF and waveform statistics. It refuses an existing output
directory and does not alter the app or safety cache. Listen to the WAVs; waveform
statistics alone do not establish intelligibility. Latest bounded host evidence:
[host validation](../../docs/testing/host-validation.md).

VieNeu is a separate **Mac experiment**, not the Android engine. Its pinned FP32
audition fetches only the required graphs and decoder (~521 MB), not the full repo:

```bash
python3.11 -m venv output/tts-env
output/tts-env/bin/pip install --no-deps vieneu==3.8.3
output/tts-env/bin/pip install sea-g2p==0.10.0 onnxruntime==1.31.0 numpy==2.4.6 soundfile==0.14.0 soxr==1.1.0 kaldi-native-fbank==1.22.3 tokenizers==0.23.3 huggingface_hub==2.2.0 PyYAML==6.0.3
output/tts-env/bin/python tools/mac-cpu-benchmark/audition_vieneu.py --download --artifacts output/tts-vieneu-artifacts --out-dir output/tts-vieneu-first
HF_HUB_OFFLINE=1 output/tts-env/bin/python tools/mac-cpu-benchmark/audition_vieneu.py --artifacts output/tts-vieneu-artifacts --out-dir output/tts-vieneu-offline
```

The minimal environment intentionally omits the SDK's web UI/legacy GPU extras.
SDK 3.8.3, model/codec revisions and the voice asset hash are recorded. Default
voice is Trúc Ly, CPU 2 threads. `--texts phrases.json` accepts a JSON object of
phrase IDs to text. The measurement is complete synthesis after warmup; it is not
streaming first-audio latency. Peak RSS uses platform units (bytes on macOS).

## Selected Phase-3 host TTS

The host adapter uses Nano/Trúc Ly/8 steps/seed 42 for Vietnamese and the existing
Piper voice for English. It keeps one Vietnamese engine resident across requests,
checks the complete pinned artifact inventory before loading, and never silently
falls back to the old Vietnamese Piper voice if Nano is corrupt or missing.

```bash
python3 model-tools-python/scripts/fetch_vieneu_nano.py
HF_HUB_OFFLINE=1 output/tts-env/bin/python tools/mac-cpu-benchmark/vieneu_bridge.py --models models --artifacts models/text_to_speech/vieneu-nano --manifest models/text_to_speech/vieneu-nano/source.json --requests testdata/mac-cpu/tts-requests.json --out-dir output/tts-selected-new
```

Use the isolated VieNeu environment above and additionally install
`sherpa-onnx==1.13.3` for English. Fetching Nano uses only Python's standard
library; `--cache /path/to/previous/nano-artifacts` avoids downloading weights
again. Existing corrupt artifacts fail verification rather than being overwritten.
Each request contains already-verbalized text, as supplied by
`SpokenTextNormalizer` in the application path. Results record the actual backend,
native sample rate and elapsed time; the first VI request includes model loading.
This adapter is Mac-only and does not mean Android TTS has been switched or ported.
The supplied request JSON is an explicit TTS smoke input, not a model quality or
speech-policy acceptance benchmark.
