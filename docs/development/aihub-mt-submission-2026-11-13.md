# AI Hub submissions: EnViT5, VinAI, PhoWhisper-base

User confirmed both MT candidates and PhoWhisper-base, then asked to submit and keep working instead of waiting for cloud compilation. Target is Galaxy S24 Ultra, QNN DLC. No QDC booking, Android model switch or final ASR selection made.

`model-tools-python/qaihub/export_submit_mt.py` exports each graph then immediately submits it without waiting for the cloud job. Three pinned MT checkpoints × encoder/decoder-init/decoder-step = nine jobs. The same full-precision pinned weights used in BTC200 are read locally on the migrated VPS; there are no new weight downloads. Local Torch/ONNX Runtime graph outputs pass allclose (rtol/atol 0.005), plus decoder top-token agreement for the export sample before each submission. Graph SHA, shape, bytes, parity errors, model revision and job IDs are written after each submission to `jobs.json` on VPS and copied into ignored local output. No automatic retry after an ambiguous submission error.

This first submission checks conversion support, **not quantization success or phone translation quality**. The graphs are static shape candidates: source128, batch1, decoder-init1, decoder-step1 with past8. They do not provide complete arbitrary-length autoregressive/beam2 decoding. Dynamic cache handling or a bucketed/static-cache deployment wrapper and decoder generation parity are still required. FP32 exports are not the final package size. Calibration/quantization and inference comparison must follow supported compilation; quality must be checked after compression rather than assumed.

PhoWhisper-base already has successful QNN DLC compile jobs `jp0j2300g` (encoder) and `jp8xm0yqg` (decoder), matching the pinned source revision. Verified both still report SUCCESS via authenticated API in this turn; reuse rather than duplicate. This older artifact was produced for QAIRT2.49 and is not automatically compatible with Android ORT QNN1.20. Earlier per-graph profiling does not establish whole-utterance latency.

Authorization and exact revisions/contracts: `config/models/aihub-submission-2026-11-13.json`. AI Hub SDK0.53 installed in the VPS experiment environment. Existing local token is passed through SSH stdin to ClientConfig in process memory and never saved to the VPS or repository. No secrets appear in CLI arguments or saved job metadata.

While AI Hub runs, TTS integration and paired BTC200 measurement were completed; see `tts-btc200-2026-11-13.md`. No need to wait for compile results to review those outputs.

## Submitted jobs

All nine submissions returned job IDs; cloud completion is intentionally not awaited.

| Graph | Job |
|---|---|
| envit5/encoder | [jgdmrnmrg](https://workbench.aihub.qualcomm.com/jobs/jgdmrnmrg/) |
| envit5/decoder-init | [j5wrdwk3p](https://workbench.aihub.qualcomm.com/jobs/j5wrdwk3p/) |
| envit5/decoder-step | [jpxz79wl5](https://workbench.aihub.qualcomm.com/jobs/jpxz79wl5/) |
| vinai-vi2en/encoder | [jpvn1qorg](https://workbench.aihub.qualcomm.com/jobs/jpvn1qorg/) |
| vinai-vi2en/decoder-init | [j578j0rnp](https://workbench.aihub.qualcomm.com/jobs/j578j0rnp/) |
| vinai-vi2en/decoder-step | [jgdmren6g](https://workbench.aihub.qualcomm.com/jobs/jgdmren6g/) |
| vinai-en2vi/encoder | [jgnw9lljg](https://workbench.aihub.qualcomm.com/jobs/jgnw9lljg/) |
| vinai-en2vi/decoder-init | [jgo91k34g](https://workbench.aihub.qualcomm.com/jobs/jgo91k34g/) |
| vinai-en2vi/decoder-step | [j578j0dqp](https://workbench.aihub.qualcomm.com/jobs/j578j0dqp/) |
