#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROFILE="${1:-vi-en-phase2}"
if (($#)); then shift; fi
exec python3 "$ROOT/tools/dev/model_sync.py" "$PROFILE" "$@"
