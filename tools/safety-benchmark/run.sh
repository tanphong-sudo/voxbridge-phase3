#!/usr/bin/env bash
# Logic-only safety benchmark for the VoxBridge model-first core.
#
# Drives the REAL DecisionEngine over a labelled corpus (tools/safety-benchmark/Benchmark.kt),
# simulating the neural MT per case to inject the exact failure modes the Safety Preservation
# Layer must catch. The oracle (required safety tokens per case) is INDEPENDENT of the engine's
# own comparator, so a green result is real evidence, not a tautology. No device, no model weights.
#
# Usage:  bash tools/safety-benchmark/run.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$ROOT/build/verify"
mkdir -p "$OUT"

echo "[1/3] Compiling core..."
kotlinc $(find "$ROOT/core/src/main/kotlin" -name '*.kt') -include-runtime -d "$OUT/core.jar"

echo "[2/3] Compiling benchmark..."
kotlinc -cp "$OUT/core.jar" "$ROOT/tools/safety-benchmark/Benchmark.kt" -include-runtime -d "$OUT/bench.jar"

echo "[3/3] Running safety benchmark..."
# tee to RESULTS.txt so the committed evidence artifact can never drift from the live run.
java -cp "$OUT/core.jar:$OUT/bench.jar" BenchmarkKt | tee "$ROOT/tools/safety-benchmark/RESULTS.txt"
