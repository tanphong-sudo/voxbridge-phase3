#!/usr/bin/env bash
# Install and launch a built VoxBridge APK on a real arm64 Android device, then fail closed on
# the native-linker/startup failures that a Gradle BUILD SUCCESSFUL cannot detect.
set -euo pipefail

APK="${1:?Usage: device-smoke.sh <apk-path>}"
PACKAGE="com.voxbridge"
ACTIVITY="com.voxbridge.ui.MainActivity"
TIMEOUT_SEC="${VOXBRIDGE_SMOKE_TIMEOUT_SEC:-300}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"

if [[ ! -x "$ADB" ]]; then
  ADB="$(command -v adb 2>/dev/null || true)"
fi
[[ -n "$ADB" && -x "$ADB" ]] || {
  echo "FAIL: adb not found; set ADB=/path/to/adb" >&2
  exit 127
}
test -s "$APK"

DEVICE_COUNT="$($ADB devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count+0 }')"
[[ "$DEVICE_COUNT" == 1 ]] || {
  echo "FAIL: expected exactly one authorized Android device, got $DEVICE_COUNT" >&2
  "$ADB" devices -l >&2
  exit 1
}

ABI="$($ADB shell getprop ro.product.cpu.abi | tr -d '\r')"
[[ "$ABI" == arm64-v8a* ]] || { echo "FAIL: device ABI is $ABI, expected arm64-v8a" >&2; exit 1; }
IS_EMULATOR="$($ADB shell getprop ro.kernel.qemu | tr -d '\r')"
if [[ "$IS_EMULATOR" == "1" && "${VOXBRIDGE_ALLOW_EMULATOR:-0}" != "1" ]]; then
  echo "FAIL: emulator detected; physical-device smoke requires real hardware" >&2
  echo "For an explicit emulator-only native startup check, set VOXBRIDGE_ALLOW_EMULATOR=1." >&2
  exit 1
fi

echo "Device ABI: $ABI"
echo "Installing: $APK"
"$ADB" install -r "$APK"

# Clear old evidence before launch so a previous crash cannot poison this result.
"$ADB" logcat -c
"$ADB" shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
"$ADB" shell am start -W -n "$PACKAGE/$ACTIVITY"

deadline=$((SECONDS + TIMEOUT_SEC))
while (( SECONDS < deadline )); do
  LOGS="$("$ADB" logcat -d -v brief 2>/dev/null || true)"

  if printf '%s\n' "$LOGS" | grep -Eq \
      '(VoxBridge|PushToTalkVM).*startup failed|UnsatisfiedLinkError|dlopen failed|FATAL EXCEPTION.*com\.voxbridge'; then
    echo "FAIL: device startup/native linker error detected" >&2
    printf '%s\n' "$LOGS" | grep -E \
      'VoxBridge|PushToTalkVM|UnsatisfiedLinkError|dlopen failed|FATAL EXCEPTION|com\.voxbridge' | tail -n 120 >&2
    exit 1
  fi

  if printf '%s\n' "$LOGS" | grep -Eq '(VoxBridge|PushToTalkVM).*startup ready: native runners loaded'; then
    echo "PASS: VoxBridge launched and native startup runners reached Ready"
    exit 0
  fi

  # A process that vanished before Ready without the expected app log is still a failed smoke.
  if ! "$ADB" shell pidof "$PACKAGE" >/dev/null 2>&1; then
    echo "FAIL: VoxBridge process exited before startup Ready" >&2
    printf '%s\n' "$LOGS" | grep -E \
      'VoxBridge|PushToTalkVM|UnsatisfiedLinkError|dlopen failed|FATAL EXCEPTION|com\.voxbridge' | tail -n 120 >&2 || true
    exit 1
  fi

  sleep 2
done

echo "FAIL: VoxBridge did not reach startup Ready within ${TIMEOUT_SEC}s" >&2
"$ADB" logcat -d -v brief | grep -E \
  'VoxBridge|PushToTalkVM|UnsatisfiedLinkError|dlopen failed|FATAL EXCEPTION|com\.voxbridge' | tail -n 120 >&2 || true
exit 1
