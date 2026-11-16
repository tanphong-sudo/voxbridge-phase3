#!/usr/bin/env bash
# REAL-MT safety benchmark for VoxBridge.
#
# Two stages:
#   1. (python) run_real_mt.py runs the REAL OPUS-MT model over corpus.tsv -> outputs.tsv
#   2. (kotlin) RealMtBenchmark.kt drives the REAL DecisionEngine over that real model text and
#      measures UNSAFE-PASS with an oracle independent of the engine's comparator.
#
# Unlike tools/safety-benchmark (failure injection / simulated MT), this proves the safety layer
# on the ACTUAL behavior of the production translation model. No device, no QNN, no AI Hub — the
# model runs fp32 on CPU only to capture its text output; the Kotlin engine is the shipping code.
#
# Usage:  bash tools/real-mt-benchmark/run.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$ROOT/tools/real-mt-benchmark"
OUT="$ROOT/build/verify"
mkdir -p "$OUT"

if [ ! -f "$HERE/outputs.tsv" ] || [ "${REGEN:-0}" = "1" ]; then
  echo "[1/4] Running REAL OPUS-MT over corpus (python)..."
  if ! python3 "$HERE/run_real_mt.py"; then
    echo "[blocked] real MT step failed (torch/transformers/network). Leaving prior outputs.tsv if any." >&2
    [ -f "$HERE/outputs.tsv" ] || exit 2
  fi
else
  echo "[1/4] Reusing existing outputs.tsv (set REGEN=1 to re-run the model)."
fi

echo "[2/4] Compiling core..."
kotlinc $(find "$ROOT/core/src/main/kotlin" -name '*.kt') -include-runtime -d "$OUT/core.jar" 2>/dev/null

echo "[3/4] Compiling real-MT benchmark..."
kotlinc -cp "$OUT/core.jar" "$HERE/RealMtBenchmark.kt" -include-runtime -d "$OUT/realmt.jar" 2>/dev/null

echo "[4/4] Running real-MT benchmark..."
# tee to RESULTS.txt so the committed evidence artifact never drifts from the live run.
java -cp "$OUT/core.jar:$OUT/realmt.jar" RealMtBenchmarkKt "$HERE/outputs.tsv" | tee "$HERE/RESULTS.txt"
