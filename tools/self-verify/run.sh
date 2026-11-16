#!/usr/bin/env bash
# Self-verification runner for the VoxBridge model-first core.
#
# Compiles the shared Kotlin core and the verification harness, then runs the harness.
# The harness drives the real DecisionEngine through the scenarios that encode the
# design's central claims (six decision states + bidirectional safety repair) and prints
# PASS/FAIL per claim. Requires only `kotlinc` + a JDK — no Gradle, no device.
#
# Usage:  bash tools/self-verify/run.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$ROOT/build/verify"
mkdir -p "$OUT"

echo "[1/3] Compiling core..."
kotlinc $(find "$ROOT/core/src/main/kotlin" -name '*.kt') -include-runtime -d "$OUT/core.jar"

echo "[2/3] Compiling harness..."
kotlinc -cp "$OUT/core.jar" "$ROOT/tools/self-verify/Verify.kt" -include-runtime -d "$OUT/verify.jar"

echo "[3/3] Running self-verification..."
# tee to RESULTS.txt so the committed evidence artifact can never drift from the live run.
java -cp "$OUT/core.jar:$OUT/verify.jar" VerifyKt | tee "$ROOT/tools/self-verify/RESULTS.txt"
