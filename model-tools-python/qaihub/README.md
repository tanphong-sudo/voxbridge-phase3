# Qualcomm AI Hub tooling

The frozen reference candidate is Qualcomm Whisper Small W8A16, release v0.64.0,
for Snapdragon 8 Gen 3 / SM8650. The complete archive/file hashes, graph names,
target and required runtime versions are in
`config/models/qnn-whisper-small-sm8650.json`. This is a **profile candidate**,
not an Android-ready package. The current app uses ORT 1.20.0; the candidate was
built for ORT 1.30.0 and QAIRT 2.50.0. Do not copy it into the app's QNN directory
or silently substitute another SoC/runtime.

From the repository root:

```bash
python3 model-tools-python/qaihub/prepare_whisper.py --bundle-dir output/qnn-whisper-sm8650 --download
python3 model-tools-python/qaihub/prepare_whisper.py --bundle-dir output/qnn-whisper-sm8650
```

The download command checks free space before staging, verifies the pinned archive
and every extracted file, and preserves an existing destination. To explicitly
check deployment versions, pass `--runtime-version` and `--soc`. Passing the
current app version `1.20.0` must fail. Passing matching version strings only
checks metadata; it does not establish that the runtime loaded on a phone.

For actual AI Hub profiles, use an isolated environment with `qai-hub==0.53.0`
(the version used here), and configure your own account outside the repository.

```bash
python3 model-tools-python/qaihub/profile_whisper.py --jobs output/qnn-profiles/jobs.json --bundle-dir output/qnn-whisper-sm8650 --submit
python3 model-tools-python/qaihub/profile_whisper.py --jobs output/qnn-profiles/jobs.json
```

Only `--submit` uploads graphs and creates missing jobs. The ledger is written
after each submission; existing encoder/decoder job IDs are reused. Without
`--submit`, the command only reads existing jobs and downloads completed profiles.
It checks target chipset and upload staging space. An ambiguous network failure
during submission must be inspected in AI Hub before retrying; no automatic retry
is enabled. Never store credentials in this repo.

Both graph profiles succeeded on Galaxy S24 Ultra / Android 14 on 2026-10-15.
The compact evidence, job URLs and all 100 timing samples per graph are in
`submission/phase-3/evaluation/qualcomm-graph-profile.json`. Decoder numbers measure
**one token step**, not an utterance. Inputs are synthetic profile inputs, so this
is neither a recognition-quality test nor a complete app/mic/speaker measurement.
Raw traces remain under ignored local output. These profiles do not authorize a
model change without the VI/EN quality checks.
