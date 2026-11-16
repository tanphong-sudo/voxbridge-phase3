# Model Tools (Python)

Build-time model tooling only — **not** part of the shipped app (the app is pure Kotlin + native
runtimes). These scripts fetch/export/quantize the models into `../models/` exactly as
[`../models/MODEL_MANIFEST.md`](../models/MODEL_MANIFEST.md) specifies, so the Android runners load
what these produce with no name drift.

## One command

```bash
pip install -r requirements.txt
bash scripts/fetch_all.sh
```

## Scripts

| Script | Does | Status |
|---|---|---|
| `scripts/paths.py` | Shared target paths (the Python side of the manifest contract) | — |
| `scripts/fetch_models.py` | Download Silero VAD + Piper vi/en/zh/ko voice bundles | ✅ runs now (network) |
| `scripts/export_opus_mt.py` | OPUS-MT 6 hops (vi-en, en-vi, en-zh, zh-vi, en-ko, ko-en — contest set incl. pivot hops) → ONNX → INT8; **writes `io.json` with the real tensor names** | ✅ (needs torch + optimum) |
| `scripts/export_selected_asr.py` | PhoWhisper Base (VI) + Whisper Small (EN) → ONNX, preserving the decoder contract and recording HF revisions | ✅ (needs torch + optimum) |
| `scripts/export_whisper_onnx.py` | Shared Whisper Base exporter/token+mel helper used by the selected-ASR pipeline | ✅ (needs torch) |
| `scripts/render_safety_cache.py` | Pre-render the safety-phrase PCM bank (4 languages × 6 phrases) with the **sherpa-onnx Python engine** (same engine as the app → cache is byte-identical to live synthesis) | ✅ (needs `pip install sherpa-onnx`) |
| `scripts/manifest_noise_plan.py` | Noise-mixing / SNR plan for the eval corpus | (pre-existing) |
| `qaihub/` | Qualcomm AI Hub profiling notes | — |

## Why `io.json` matters

`export_opus_mt.py` records the REAL input/output tensor names of every exported graph in
`io.json` — build-time evidence of what optimum actually produced (kept next to the graphs, and
printed so a human can eyeball it once). The Android `OnnxTranslator` itself does **not** depend
on the file: at load time it introspects each session's `inputNames`/`outputNames` directly, so
whatever the exporter version produces is what the runner binds. `io.json` stays useful as
build-time documentation and for the e2e-jvm assertions; do not re-introduce a runtime read of it.

Do not add API keys or credentials to this repository.
# Integrity and safe publication

Model exports and bundles now stage on the destination filesystem, validate,
rename the previous tree to a unique backup, and restore it if publication fails.
Leftover `.backup-*` directories after a power/process interruption are retained
for recovery, never automatically pruned. Do not delete them without inspecting
which tree is complete. Piper extraction requires Python's safe `tarfile` data
filter; unsupported older Python fails closed.

Device synchronization writes `models/integrity.sha256` (`SHA256 size relative-path`)
for the selected profile. New transfers are SHA-256 checked on the phone before
publication. Unchanged trees normally use the marker from the earlier successful
content verification; this is explicitly **not** a new full-device hash pass.

```bash
bash tools/dev/sync-models-device.sh vi-en-mlkit --verify-full
```

`--verify-full` hashes host contents without persisted caches and rechecks all
device contents, repairing mismatched trees. For faster subsequent host checks,
`--trust-metadata-cache` explicitly opts into size/mtime/ctime/inode/device digest
caching under `~/.cache/voxbridge/sync-digests.json`; metadata is not a cryptographic
integrity guarantee. `--verify-content` independently rechecks device bytes.
Default host checking remains strict. Paths in the integrity file are relative to
the model root; markers, version.txt, and the integrity file itself are excluded.
