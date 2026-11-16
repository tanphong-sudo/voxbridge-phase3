# 1Voice / VoxBridge VPS migration — 2026-11-13

User authorized moving all 1Voice work, explicitly including the teammate's ASR, to the new VPS and deleting the verified source copies.

## Active server

- SSH: `ssh -i "<ssh-key>" -o IdentitiesOnly=yes opc@<vps-host>`
- Platform: Oracle Linux 9.8 x86_64; approximately 46 GiB RAM and 483 GiB root filesystem.
- Latest working source, Git history, uncommitted changes and local evaluation evidence: `/home/opc/voxbridge-current`.
- Original VPS source and model tree retained at `/home/opc/voxbridge`.
- VinAI v2, EnViT5, punctuation model, Python environment and MT runs: `/home/opc/voxbridge-mt3-20261113`.
- ASR export environment: `/home/opc/envs/export`.
- ASR exports: `/home/opc/exports`.
- Original ASR/VAD/TTS models: `/home/opc/voxbridge/models`.
- ASR model selector links: `/home/opc/models-phosmall`, `/home/opc/models-wbase`, `/home/opc/models-phosmall-int8`.
- BTC/noise data: `/home/opc/data`; ASR evaluations: `/home/opc/runs`.
- Android SDK, Gradle and Kotlin caches preserve their existing `/home/opc/...` paths.
- The latest source uses links to the migrated model tree and JVM cache rather than duplicating weights.

## Transfer verification

The 14 explicitly selected source roots contained **73,292 regular files**, **95,514 entries** (including directories/links), and **14,369,817,472 regular-file bytes**. Every regular file was checked with SHA-256; path, type, mode and link target were also compared. A second source inventory matched the first, establishing source stability during transfer. Destination matched all records. No private SSH key was copied to either server; a temporary local SSH agent enabled direct rsync and was terminated afterward.

Verification evidence is in `/home/opc/1voice-migration-evidence` on the new server and local ignored `output/phase3/vps-migration-20261113/`.

## Runtime checks on new server

- Python 3.11, Torch 2.5.1+cpu, Transformers 4.51.3, ORT 1.20.0, sacrebleu 2.5.1, SentencePiece 0.2.0, ONNX 1.17.0 imported from their preserved environments.
- All 15 ASR graphs loaded: PhoWhisper base, Whisper small, PhoWhisper small FP32, PhoWhisper small INT8, Whisper base. All five encoders completed zero-input inference. Decoder loading is a compatibility smoke test, not ASR quality validation.
- VAD and six TTS graphs loaded; model-selector links resolved.
- VinAI VI→EN and EN→VI, EnViT5 VI→EN and EN→VI completed beam2 translations from local pinned checkpoints.
- Latest working source: 150/150 host tests and 100/100 phase3 Python tests passed on the new server.

## Source cleanup boundary

The old address is `<vps-host>`; it is retired for 1Voice work. Deletion is limited to the 14 verified 1Voice roots, after a fresh source checksum comparison immediately before removal. Shared OS packages and unrelated projects are outside this deletion scope. `.local/bin/python3.12` belongs to ShadowFleet and is excluded; only `.local/lib/python3.11` (ASR normalizer libraries) is migrated. Generic pip/uv download caches are not model/source directories and remain untouched.

Use `output/phase3/vps-migration-20261113/source-removal.json` and the corresponding copied evidence on the new server for actual source-removal completion, rather than inferring it from this document.

## Punctuation continuation

The interrupted ablation is preserved in `/home/opc/voxbridge-current/output/phase3/punctuation-next`: 210 frozen sources, five variants, 300 exact cached MT inputs reused and 290 additional unique MT inputs planned per model. The sources include 100 BTC VI texts, 50 prior controls, 10 adversarial cases, 40 fresh frozen controls, and 10 actual saved ASR transcripts. Source edits are experimental and punctuation remains off by default until the comparison is reviewed. No QDC job is started by migration.
