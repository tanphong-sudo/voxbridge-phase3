#!/usr/bin/env bash
# VoxBridge — one-touch bring-up.
#
#   bash setup.sh                 # verify core (no models) + fetch models + verify again
#   bash setup.sh --verify-only   # just run the two harnesses (no downloads)
#   bash setup.sh --models-only   # just fetch/export the model set
#
# The verified core (decision engine + safety layer) needs NO models — §1 runs first so you always
# see the proof before any download. See docs/development/onnx-integration.md for the Android build + device steps.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

MODE="${1:-all}"

verify() {
    echo "== [verify] self-verification (70/70 expected) =="
    bash tools/self-verify/run.sh | tail -1
    echo "== [verify] safety benchmark (UNSAFE-PASS 0 expected) =="
    bash tools/safety-benchmark/run.sh | tail -1
    echo "== [verify] mel DSP front end (8/8 expected) =="
    bash tools/dsp-verify/run.sh | tail -1
    echo "== [verify] runtime mock harness (main: 707 checks + installer: 22 expected) =="
    bash tools/mock-jvm/run.sh | grep -E "ALL OK|checks" | tail -3
}

fetch() {
    echo "== [models] fetching the auto set per MODEL_MANIFEST =="
    if ! command -v python3 >/dev/null; then echo "python3 required"; exit 1; fi
    pip install -q -r model-tools-python/requirements.txt || {
        echo "(pip install failed — install deps manually, then re-run --models-only)"; return 1; }
    bash model-tools-python/scripts/fetch_all.sh
}

case "$MODE" in
    --verify-only) verify ;;
    --models-only) fetch ;;
    all)
        verify
        echo ""
        echo ">> Core verified. Fetching models (Ctrl-C to skip and stay logic-only)..."
        fetch || echo "(model fetch skipped/failed — core is still verified above)"
        echo ""
        echo ">> Next: push models to device + build app — see docs/development/onnx-integration.md §2-3."
        ;;
    *) echo "usage: bash setup.sh [--verify-only|--models-only]"; exit 1 ;;
esac
