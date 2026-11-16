#!/usr/bin/env bash
# One command to fetch + export every AUTO model into models/ per MODEL_MANIFEST.md.
#
#   bash model-tools-python/scripts/fetch_all.sh
#
# Brings up the complete model set, including the selected PhoWhisper Base + Whisper Small ASRs.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

PY="${PYTHON:-python3}"

echo "== [1/4] Phase-2 Silero VAD + VI/EN Piper voices =="
"$PY" fetch_models.py --phase2

echo "== [2/4] Phase-2 OPUS-MT VI<->EN -> ONNX -> INT8 =="
"$PY" export_opus_mt.py --dir vi-en
"$PY" export_opus_mt.py --dir en-vi

echo "== [3/4] Selected ASRs: PhoWhisper Base (VI) + Whisper Small (EN) =="
"$PY" export_selected_asr.py "$HERE/../../models"

echo "== [4/4] Safety-phrase PCM cache =="
# NO || echo swallow (Round-4 review MED): a failed cache render must fail THIS script loudly —
# otherwise notebook 01 succeeds and the APK ships a NO_SPEECH / ASK_REPEAT that produces no
# audio at all (worker-safety prompts). On Colab sherpa-onnx is pinned in the venv, so this
# runs; locally, install requirements.txt first.
"$PY" render_safety_cache.py --phase2

echo ""
echo "== DONE (auto set) =="
echo "Verify the ⚠️ rows in models/MODEL_MANIFEST.md (esp. io.json tensor names)."
