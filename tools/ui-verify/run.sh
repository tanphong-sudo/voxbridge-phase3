#!/usr/bin/env bash
# UI trust-logic verification for VoxBridge.
#
# TrustLevel.kt is the only safety-meaningful piece of the Compose UI (decision -> traffic light,
# ASK_REPEAT reason -> physical retry hint, hazard-cue scan). It has NO Android/Compose imports, so
# it compiles and runs under plain `kotlinc` — exactly like the decision-core harnesses. The Compose
# screens need the Android SDK + a device and are Phase-3 render work; this harness pins the logic
# whose correctness carries a worker-safety meaning.
#
# Usage:  bash tools/ui-verify/run.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$ROOT/build/ui-verify"
mkdir -p "$OUT"

TRUST="$ROOT/android/app/src/main/java/com/voxbridge/ui/TrustLevel.kt"

echo "[1/2] Compiling TrustLevel + harness (no Android SDK needed)..."
kotlinc "$TRUST" "$ROOT/tools/ui-verify/TrustVerify.kt" -include-runtime -d "$OUT/ui-verify.jar"

echo "[2/2] Running UI trust-logic verification..."
java -cp "$OUT/ui-verify.jar" TrustVerifyKt | tee "$ROOT/tools/ui-verify/RESULTS.txt"
