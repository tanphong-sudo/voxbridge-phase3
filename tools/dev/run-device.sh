#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PACKAGE="com.voxbridge"
ACTIVITY="com.voxbridge.ui.MainActivity"
NO_LOGCAT=0
[[ "${1:-}" == "--no-logcat" ]] && NO_LOGCAT=1

[[ -x "$ADB" ]] || { echo "FAIL: adb not found at $ADB" >&2; exit 127; }
DEVICE_COUNT="$($ADB devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count+0 }')"
[[ "$DEVICE_COUNT" == 1 ]] || {
  echo "FAIL: expected exactly one authorized Android device, got $DEVICE_COUNT" >&2
  "$ADB" devices -l >&2
  exit 1
}

export VOXBRIDGE_JAVA_HOME="${VOXBRIDGE_JAVA_HOME:-$HOME/.cache/voxbridge/jdk17/Contents/Home}"
if [[ ! -x "$VOXBRIDGE_JAVA_HOME/bin/java" ]]; then
  echo "FAIL: JDK 17 missing at $VOXBRIDGE_JAVA_HOME" >&2
  echo "Run: bash tools/dev/bootstrap-mac.sh" >&2
  exit 2
fi
export JAVA_HOME="$VOXBRIDGE_JAVA_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$ROOT"
./gradlew :android:app:assembleDebugCpu
APK="$ROOT/android/app/build/outputs/apk/debugCpu/app-debugCpu.apk"
[[ -s "$APK" ]] || { echo "FAIL: APK not produced: $APK" >&2; exit 1; }
"$ADB" install -r "$APK"
"$ADB" shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
"$ADB" shell am start -W -n "$PACKAGE/$ACTIVITY"

if [[ "$NO_LOGCAT" == 1 ]]; then
  exit 0
fi

exec "$ADB" logcat -v color -s \
  VoxBridge:I AudioCapture:I SileroVad:I WhisperAsr:I Translator:I SafetyLayer:I \
  PiperTts:I ModelRepository:I PushToTalkVM:I AndroidRuntime:E '*:S'
