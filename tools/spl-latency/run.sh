#!/usr/bin/env bash
# Safety-Preservation-Layer latency micro-benchmark.
#
# Measures ONLY the pure-Kotlin decision-path overhead (extract+compare+repair) on the laptop JVM,
# driving the REAL DecisionEngine over the safety corpus with the model stubbed as a table lookup.
# This is the layer's ADDED overhead, NOT end-to-end turnaround and NOT the NPU/QNN path — those
# remain Phase-3 on-device measurements (docs 04, 13). It exists so doc 06's "< 20 ms layer budget"
# is a MEASURED claim about our code rather than an asserted one.
#
# Usage:  bash tools/spl-latency/run.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$ROOT/build/verify"
mkdir -p "$OUT"

echo "[1/3] Compiling core..."
kotlinc $(find "$ROOT/core/src/main/kotlin" -name '*.kt') -include-runtime -d "$OUT/core.jar" 2>/dev/null

echo "[2/3] Compiling latency benchmark..."
kotlinc -cp "$OUT/core.jar" "$ROOT/tools/spl-latency/Latency.kt" -include-runtime -d "$OUT/spllat.jar" 2>/dev/null

echo "[3/3] Running latency benchmark..."
java -cp "$OUT/core.jar:$OUT/spllat.jar" LatencyKt "$ROOT/tools/real-mt-benchmark/outputs.tsv" | tee "$ROOT/tools/spl-latency/RESULTS.txt"
