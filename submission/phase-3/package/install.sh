#!/bin/bash
# Install the VoxBridge Phase 3 offline package on a connected Snapdragon 8 Gen 3 (SM8650) phone.
#
# Run from the unzipped package folder, which contains:
#   voxbridge-*.apk   the app
#   models/           the on-device model set, including integrity.sha256
# Needs only adb (Android platform-tools) and a USB cable; no network.
#
# Usage: bash install.sh [adb-serial]
set -euo pipefail

cd "$(dirname "$0")"
ADB=(adb)
if [ $# -ge 1 ]; then ADB=(adb -s "$1"); fi

APK=$(ls voxbridge-*.apk | head -n 1)
PKG=com.voxbridge
DEST=/sdcard/Android/data/$PKG/files

[ -f "$APK" ] || { echo "No voxbridge-*.apk next to this script" >&2; exit 1; }
[ -f models/integrity.sha256 ] || { echo "models/integrity.sha256 is missing" >&2; exit 1; }

echo "1/4 Installing $APK"
"${ADB[@]}" install -r "$APK"

echo "2/4 Copying models (about 3 GB; a few minutes over USB)"
"${ADB[@]}" shell mkdir -p "$DEST/models"
for part in vad translation text_to_speech speech_to_text; do
    "${ADB[@]}" push "models/$part" "$DEST/models/"
done
"${ADB[@]}" push models/integrity.sha256 "$DEST/models/integrity.sha256"
"${ADB[@]}" shell rm -f "$DEST/models/.verified-integrity"

echo "3/4 Handing the files to the app"
# adb may write as root on engineering builds; the app must own its files to read them.
UID_NUM=$("${ADB[@]}" shell "dumpsys package $PKG | grep -m1 -E '(userId|appId)='" | sed -E 's/.*(userId|appId)=([0-9]+).*/\2/' | tr -d '\r')
APP_USER="u0_a$((UID_NUM - 10000))"
"${ADB[@]}" shell "chown -R $APP_USER:ext_data_rw $DEST 2>/dev/null; chmod -R u+rwX,g+rwX $DEST/models 2>/dev/null; true"

echo "4/4 Granting microphone permission"
"${ADB[@]}" shell pm grant "$PKG" android.permission.RECORD_AUDIO || true

echo "Done. Turn on airplane mode, then open VoxBridge."
echo "First launch verifies the models (about one minute) before the 'Giữ để nói' button appears."
