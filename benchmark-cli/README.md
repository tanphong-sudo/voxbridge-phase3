# Synthetic decision harness

This CLI does **not** run ASR, translation, VAD or TTS models. It streams a CSV
manifest through the core decision rules using supplied/expected translations.
Every JSONL row declares `real_model_inference: false` and synthetic provenance.
Never cite its output as model accuracy, inference speed, RAM or device results.

CSV supports commas, escaped quotes, CRLF and multiline quoted fields. Fields are
limited to 1 MiB characters and rows to 4 MiB characters / 1025 fields to reject malformed inputs. Use the real
Mac/Android pipeline for model evaluation, recording model/hardware provenance.
