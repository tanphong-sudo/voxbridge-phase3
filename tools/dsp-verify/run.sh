#!/usr/bin/env bash
# DSP self-verification for the pure-Kotlin MelSpectrogram front end.
#
# MelSpectrogram.kt has NO Android dependency (java.nio + kotlin.math only), so it compiles and runs
# under plain kotlinc — exactly like the decision-core harnesses. This verifies the new audio DSP
# is "measured, not narrated".
#
# Usage:  bash tools/dsp-verify/run.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$ROOT/build/dsp-verify"
mkdir -p "$OUT"

MEL="$ROOT/android/app/src/main/java/com/voxbridge/runtime/MelSpectrogram.kt"

echo "[1/2] Compiling MelSpectrogram + harness (no Android SDK needed)..."
kotlinc "$MEL" "$ROOT/android/app/src/main/java/com/voxbridge/runtime/RealFft400.kt" "$ROOT/tools/dsp-verify/MelVerify.kt" -include-runtime -d "$OUT/mel.jar"

echo "[2/2] Running DSP self-verification..."
java -cp "$OUT/mel.jar" MelVerifyKt | tee "$ROOT/tools/dsp-verify/RESULTS.txt"
