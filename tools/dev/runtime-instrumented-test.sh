#!/usr/bin/env bash
# Real Android-runtime inference test with external dev models.
#
# Gradle's connectedDebugAndroidTest owns the target-app lifecycle and may uninstall/reinstall it.
# VoxBridge keeps dev models in the app-specific external-files directory, so that lifecycle can
# erase the model tree before instrumentation starts. Keep the order explicit here:
# build -> install target -> sync models -> install test APK -> run instrumentation.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROFILE="${1:-vi-en-phase2}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
JDK17="${VOXBRIDGE_JAVA_HOME:-$HOME/.cache/voxbridge/jdk17/Contents/Home}"

if [[ ! -x "$ADB" ]]; then
  ADB="$(command -v adb 2>/dev/null || true)"
fi
[[ -n "$ADB" && -x "$ADB" ]] || {
  echo "FAIL: adb not found; set ADB=/path/to/adb" >&2
  exit 127
}
[[ -x "$JDK17/bin/java" ]] || {
  echo "FAIL: JDK 17 missing at $JDK17" >&2
  echo "Run: bash tools/dev/bootstrap-mac.sh" >&2
  exit 2
}

DEVICE_COUNT="$("$ADB" devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count+0 }')"
[[ "$DEVICE_COUNT" == 1 ]] || {
  echo "FAIL: expected exactly one authorized Android device, got $DEVICE_COUNT" >&2
  "$ADB" devices -l >&2
  exit 1
}
ABI="$("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')"
[[ "$ABI" == arm64-v8a* ]] || {
  echo "FAIL: runtime inference test requires arm64-v8a, got $ABI" >&2
  exit 1
}

export ADB
export JAVA_HOME="$JDK17"
export PATH="$JAVA_HOME/bin:$PATH"
cd "$ROOT"

echo "[1/5] Building target + instrumentation APKs..."
./gradlew :android:app:assembleDebug :android:app:assembleDebugAndroidTest

TARGET_APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
[[ -s "$TARGET_APK" ]] || { echo "FAIL: target APK missing: $TARGET_APK" >&2; exit 1; }
[[ -s "$TEST_APK" ]] || { echo "FAIL: test APK missing: $TEST_APK" >&2; exit 1; }

echo "[2/5] Installing target APK + granting runtime permissions for deterministic headless test..."
"$ADB" install -r -g "$TARGET_APK"

echo "[3/5] Syncing external model profile: $PROFILE"
python3 "$ROOT/tools/dev/model_sync.py" "$PROFILE"

echo "[4/5] Installing instrumentation APK..."
"$ADB" install -r "$TEST_APK"

echo "[5/5] Running real Piper -> Silero VAD -> ASR -> DecisionEngine/MT instrumentation..."
INSTRUMENT_OUTPUT="$("$ADB" shell am instrument -w \
  com.voxbridge.test/androidx.test.runner.AndroidJUnitRunner 2>&1)"
printf '%s\n' "$INSTRUMENT_OUTPUT"

# `am instrument -w` can exit 0 even when JUnit reports failures. Treat the textual runner result
# as the authoritative gate so this helper can never print a false PASS.
if grep -q 'FAILURES!!!' <<<"$INSTRUMENT_OUTPUT" || \
   grep -q '^INSTRUMENTATION_FAILED:' <<<"$INSTRUMENT_OUTPUT" || \
   ! grep -Eq '^OK \([0-9]+ tests?\)$' <<<"$INSTRUMENT_OUTPUT"; then
  echo "FAIL: Android runtime inference instrumentation did not pass" >&2
  exit 1
fi

echo "PASS: Android runtime inference instrumentation completed for $PROFILE"
