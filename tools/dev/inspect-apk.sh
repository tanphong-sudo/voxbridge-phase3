#!/usr/bin/env bash
# Inspect a built APK. Device installation requires --install-device <serial> explicitly.
set -euo pipefail

usage() {
    printf 'Usage: inspect-apk.sh <apk-path> [build-tools-dir] [--install-device <serial>]\n' >&2
    exit 2
}
[[ $# -ge 1 ]] || usage
APK="$1"
shift
BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-${ANDROID_HOME:-/opt/android-sdk}/build-tools/34.0.0}"
if [[ $# -gt 0 && "$1" != --* ]]; then
    BUILD_TOOLS="$1"
    shift
fi
DEVICE=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --install-device)
            [[ $# -ge 2 && -n "$2" && "$2" != -* && -z "$DEVICE" ]] || usage
            DEVICE="$2"
            shift 2
            ;;
        *) usage ;;
    esac
done
AAPT2="$BUILD_TOOLS/aapt2"
APKSIGNER="$BUILD_TOOLS/apksigner"

test -s "$APK"
printf 'APK: %s\n' "$APK"
ls -lh "$APK"
printf '\n== SHA-256 ==\n'
sha256sum "$APK"
printf '\n== Signing ==\n'
if [[ -x "$APKSIGNER" ]]; then
    "$APKSIGNER" verify --verbose --print-certs "$APK"
elif [[ -f "$BUILD_TOOLS/lib/apksigner.jar" ]]; then
    java -jar "$BUILD_TOOLS/lib/apksigner.jar" verify --verbose --print-certs "$APK"
else
    printf 'MISSING apksigner binary and %s/lib/apksigner.jar\n' "$BUILD_TOOLS" >&2
    exit 127
fi
printf '\n== Package metadata ==\n'
"$AAPT2" dump badging "$APK"
printf '\n== ZIP contents ==\n'
python3 - "$APK" <<'PY'
import sys
import zipfile
from collections import defaultdict

apk = sys.argv[1]
with zipfile.ZipFile(apk) as archive:
    entries = archive.infolist()
    total_compressed = sum(item.compress_size for item in entries)
    total_uncompressed = sum(item.file_size for item in entries)
    groups = {
        "models": "assets/models/",
        "PhoWhisper Base": "assets/models/speech_to_text/phowhisper-base-onnx/",
        "Whisper Small": "assets/models/speech_to_text/whisper-small-onnx/",
        "OPUS-MT": "assets/models/translation/",
        "Piper": "assets/models/text_to_speech/piper/",
        "lib/arm64-v8a": "lib/arm64-v8a/",
    }
    asr_dirs = sorted({
        item.filename.split('/')[3]
        for item in entries
        if item.filename.startswith('assets/models/speech_to_text/') and item.filename.count('/') >= 4
    })
    expected_asr_dirs = ['phowhisper-base-onnx', 'whisper-small-onnx']
    print('ASR directories:', asr_dirs)
    if asr_dirs != expected_asr_dirs:
        raise SystemExit(f'FAIL: expected exactly {expected_asr_dirs}, got {asr_dirs}')
    print(f"entries: {len(entries)}")
    print(f"ZIP compressed bytes: {total_compressed:,}")
    print(f"ZIP uncompressed bytes: {total_uncompressed:,}")
    for label, prefix in groups.items():
        selected = [item for item in entries if item.filename.startswith(prefix)]
        print(f"{label}: compressed={sum(x.compress_size for x in selected):,} "
              f"uncompressed={sum(x.file_size for x in selected):,} files={len(selected)}")

    abis = sorted({item.filename.split("/")[1] for item in entries if item.filename.startswith("lib/") and item.filename.count("/") >= 2})
    print("native ABIs:", ", ".join(abis) or "none")
    native = [item.filename for item in entries if item.filename.startswith("lib/")]
    ort_native = [name for name in native if name.startswith("lib/arm64-v8a/") and "onnxruntime" in name]
    print("ONNX Runtime arm64 libraries:", ort_native or "MISSING")
    for needle in ("libonnxruntime.so", "libonnxruntime4j_jni.so", "libsherpa-onnx-jni.so"):
        matches = [name for name in native if name.endswith("/" + needle)]
        print(f"{needle}: {matches or 'MISSING'}")
    qnn = [name for name in native if "qnn" in name.lower()]
    print("QNN libraries:", qnn or "none")

    zip64 = any(
        item.extract_version >= 45 or item.file_size >= 0xFFFFFFFF or
        item.compress_size >= 0xFFFFFFFF or item.header_offset >= 0xFFFFFFFF
        for item in entries
    )
    print("ZIP64 entries:", "yes" if zip64 else "no")
    print("largest APK entries:")
    for item in sorted(entries, key=lambda x: x.file_size, reverse=True)[:20]:
        print(f"  {item.file_size:>12,}  {item.filename}")
PY

if [[ -n "$DEVICE" ]]; then
    command -v adb >/dev/null 2>&1 || { printf 'ADB is unavailable\n' >&2; exit 127; }
    STATE="$(adb -s "$DEVICE" get-state)"
    [[ "$STATE" == device ]] || { printf 'Selected device is not authorized/ready\n' >&2; exit 1; }
    printf '\n== ADB install on selected device ==\n'
    adb -s "$DEVICE" install "$APK"
fi
