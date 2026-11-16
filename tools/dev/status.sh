#!/usr/bin/env bash
# Validate the selected Phase-2 VI/EN cache without changing or exporting models.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec python3 "$ROOT/tools/dev/model_bundle.py" "${1:-models}"
