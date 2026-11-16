#!/usr/bin/env bash
# Run validation without swallowing a failed model-contract test.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODELS_DIR="${1:-$ROOT/models}"
cd "$ROOT"

python3 scripts/validate_project.py
bash tools/self-verify/run.sh
bash tools/safety-benchmark/run.sh
bash tools/dsp-verify/run.sh
bash tools/mock-jvm/run.sh
bash tools/dev/status.sh "$MODELS_DIR"
bash tools/e2e-jvm/run.sh "$MODELS_DIR"
