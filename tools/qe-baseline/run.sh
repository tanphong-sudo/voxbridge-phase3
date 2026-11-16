#!/usr/bin/env bash
# QE-baseline experiment for VoxBridge (judge Rank-2: prove the novelty, don't assert it).
#
# Scores the REAL OPUS-MT outputs (tools/real-mt-benchmark/outputs.tsv) with a reference-free
# Quality-Estimation model and shows that a QE confidence gate cannot separate the 4 fluent-but-
# unsafe corruptions from correct translations — the empirical basis for "refuse on a verified
# contradiction, not on a fluency score". Offline laptop EVAL of captured text; NOT device inference,
# NOT part of the shipped pipeline.
#
# unbabel-comet pulls its own torch/transformers, so it MUST run in an isolated venv to avoid
# disturbing the real-mt-benchmark environment.
#
# Usage:
#   python3 -m venv /tmp/qe-venv && /tmp/qe-venv/bin/pip install unbabel-comet
#   bash tools/qe-baseline/run.sh            # uses /tmp/qe-venv by default
#   QE_PY=/path/to/venv/bin/python bash tools/qe-baseline/run.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
QE_PY="${QE_PY:-/tmp/qe-venv/bin/python}"

if [ ! -x "$QE_PY" ]; then
  echo "[blocked] QE python not found at $QE_PY" >&2
  echo "  create it: python3 -m venv /tmp/qe-venv && /tmp/qe-venv/bin/pip install unbabel-comet" >&2
  exit 2
fi

# tee to RESULTS.txt is done inside score.py; this wrapper just invokes it and surfaces the tail.
"$QE_PY" "$ROOT/tools/qe-baseline/score.py"
