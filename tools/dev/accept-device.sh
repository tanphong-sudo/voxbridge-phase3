#!/usr/bin/env bash
# One-command physical-device acceptance gate for the local Mac workflow:
# build debugCpu -> install -> sync selected external models -> relaunch -> native startup smoke.
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

DEVICE_COUNT="$($ADB devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count+0 }')"
[[ "$DEVICE_COUNT" == 1 ]] || {
  echo "FAIL: expected exactly one authorized Android device, got $DEVICE_COUNT" >&2
  "$ADB" devices -l >&2
  exit 1
}

ABI="$($ADB shell getprop ro.product.cpu.abi | tr -d '\r')"
[[ "$ABI" == arm64-v8a* ]] || {
  echo "FAIL: physical acceptance requires arm64-v8a, got $ABI" >&2
  exit 1
}
IS_EMULATOR="$($ADB shell getprop ro.kernel.qemu | tr -d '\r')"
[[ "$IS_EMULATOR" != "1" ]] || {
  echo "FAIL: emulator detected; accept-device.sh is intentionally physical-hardware only" >&2
  exit 1
}

[[ -x "$JDK17/bin/java" ]] || {
  echo "FAIL: JDK 17 missing at $JDK17" >&2
  echo "Run: bash tools/dev/bootstrap-mac.sh" >&2
  exit 2
}

export ADB
export JAVA_HOME="$JDK17"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$ROOT"
echo "[1/4] Building debugCpu..."
./gradlew :android:app:assembleDebugCpu
APK="$ROOT/android/app/build/outputs/apk/debugCpu/app-debugCpu.apk"
[[ -s "$APK" ]] || { echo "FAIL: APK not produced: $APK" >&2; exit 1; }

echo "[2/4] Installing debugCpu so the app can report its Android-derived model root..."
"$ADB" install -r "$APK"

echo "[3/4] Syncing external model profile: $PROFILE"
bash "$ROOT/tools/dev/sync-models-device.sh" "$PROFILE"

echo "[4/4] Relaunching and waiting for all native runners to reach Ready..."
bash "$ROOT/tools/dev/device-smoke.sh" "$APK"

echo "PASS: physical-device startup acceptance completed for $PROFILE"
echo "Next manual acceptance: hold PTT and speak one VI utterance, then one EN utterance; inspect Diagnostics/Logcat."
