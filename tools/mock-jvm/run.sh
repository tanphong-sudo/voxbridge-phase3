#!/usr/bin/env bash
# =============================================================================
# VoxBridge — JVM mock harness runner (no Android, no models needed)
#
# Compiles the REAL repo Kotlin (runtime files under test) against the API stubs in
# src/ and runs the scripted-session test suites in test/. Because the runtime
# files are compiled straight from the repo, this harness can never drift from
# the app code — a stale copy cannot exist for the files it covers.
#
# Usage:
#   bash tools/mock-jvm/run.sh              # both suites (default)
#   bash tools/mock-jvm/run.sh --main       # TestMainKt only  (707 checks)
#   bash tools/mock-jvm/run.sh --installer  # TestInstallerKt (22 checks)
#
# Env: JAVA_HOME optional (pins JDK 17 when the system JDK is 21+, like Colab).
# Idempotent: REUSES the tools/e2e-jvm/.cache (same pinned JDK 17 / kotlinc / jars — one
# download serves both harnesses; everything there is gitignored).
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
CACHE="$ROOT/tools/e2e-jvm/.cache"
SUITE="${1:---all}"

KOTLIN_VER="2.0.21"   # same compiler as tools/e2e-jvm/run.sh; -language-version 1.9
ORT_VER="1.20.0"      # API stubs mirror this onnxruntime-java version
JSON_VER="20240303"   # == org.json in app/build.gradle.kts

fetch() { # atomic download (a partial transfer must never enter the cache)
  [ -s "$2" ] && return 0
  echo "downloading $(basename "$2") ..." >&2
  rm -f "$2.part"
  if curl -fsSL --retry 3 -o "$2.part" "$1" && [ -s "$2.part" ]; then mv -f "$2.part" "$2"
  elif wget -qO "$2.part" "$1" && [ -s "$2.part" ]; then mv -f "$2.part" "$2"
  else rm -f "$2.part"; echo "ERROR: download failed: $1" >&2; return 1; fi
}

# --- JDK: kotlinc cannot parse 21+ release strings — pin 17 when needed (same as e2e-jvm)
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v "$JAVA_BIN" >/dev/null 2>&1 || JAVA_BIN="java"
JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')" || JAVA_MAJOR=0
if [ "$JAVA_MAJOR" -ge 21 ] 2>/dev/null; then
  JDK17=""
  if [ "$(uname -s)" = "Darwin" ] && [ -x /usr/libexec/java_home ]; then
    JDK17="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  fi
  if [ -z "$JDK17" ]; then
    JDK17="$(ls -d /usr/lib/jvm/java-17-openjdk-* 2>/dev/null | head -1 || true)"
  fi
  if [ -n "$JDK17" ] && ! "$JDK17/bin/java" -version 2>&1 | head -1 | grep -q 'version "17'; then JDK17=""; fi
  if [ -z "$JDK17" ] || [ ! -x "$JDK17/bin/java" ]; then
    echo "system JDK is $JAVA_MAJOR — fetching Temurin 17 (one-time, cached) ..." >&2
    OS=$(uname -s); ARCH=$(uname -m)
    case "$OS/$ARCH" in
      Darwin/arm64)  TJ=mac;   TA=aarch64 ;;
      Darwin/x86_64) TJ=mac;   TA=x64 ;;
      Linux/x86_64)  TJ=linux; TA=x64 ;;
      Linux/aarch64) TJ=linux; TA=aarch64 ;;
      *) echo "ERROR: unsupported platform $OS/$ARCH" >&2; exit 2 ;;
    esac
    mkdir -p "$CACHE/jdk17"
    fetch "https://api.adoptium.net/v3/binary/latest/17/ga/$TJ/$TA/jdk/hotspot/normal/eclipse" "$CACHE/jdk17.tar.gz"
    # Skip re-extraction when the cached tarball was already unpacked (Colab re-runs would
    # otherwise untar 177 MB every run — Round-4 review LOW).
    if ! find "$CACHE/jdk17" -maxdepth 5 -type d -name Home 2>/dev/null | grep -q .; then
      tar -xzf "$CACHE/jdk17.tar.gz" -C "$CACHE/jdk17"
    fi
    JDK17="$(find "$CACHE/jdk17" -maxdepth 5 -type d -name Home | head -1 || true)"
    [ -z "$JDK17" ] && JDK17="$(find "$CACHE/jdk17" -maxdepth 2 -type d -name 'jdk-17*' | head -1 || true)"
  fi
  [ -n "$JDK17" ] && [ -x "$JDK17/bin/java" ] || { echo "ERROR: no JDK 17 available" >&2; exit 2; }
  export JAVA_HOME="$JDK17"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

mkdir -p "$CACHE"
KOTLINC="$CACHE/kotlinc-$KOTLIN_VER/bin/kotlinc"
if [ ! -x "$KOTLINC" ]; then
  fetch "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VER/kotlin-compiler-$KOTLIN_VER.zip" "$CACHE/kotlinc-$KOTLIN_VER.zip"
  rm -rf "$CACHE/kotlinc-$KOTLIN_VER"
  unzip -q "$CACHE/kotlinc-$KOTLIN_VER.zip" -d "$CACHE/_unz"
  mv "$CACHE/_unz/kotlinc" "$CACHE/kotlinc-$KOTLIN_VER"
  rmdir "$CACHE/_unz"
fi

M2="https://repo.maven.apache.org/maven2"
fetch "$M2/com/microsoft/onnxruntime/onnxruntime/$ORT_VER/onnxruntime-$ORT_VER.jar" "$CACHE/onnxruntime-$ORT_VER.jar"
fetch "$M2/org/json/json/$JSON_VER/json-$JSON_VER.jar" "$CACHE/json-$JSON_VER.jar"
fetch "$M2/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar" "$CACHE/slf4j-api-2.0.13.jar"
fetch "$M2/org/slf4j/slf4j-nop/2.0.13/slf4j-nop-2.0.13.jar" "$CACHE/slf4j-nop-2.0.13.jar"

CP="$(ls "$CACHE"/*.jar | tr '\n' ':')"
CP="${CP%:}"

# REAL repo code (never copies — see README): the runtime files under test (10 of the 12 in
# runtime/ — PiperTts.kt and AndroidModelPaths.kt need un-stubbed sherpa/android APIs),
# compiled against the core-contract STUBS in src/ (the stubs replicate the core port classes
# the runtime needs; core logic itself is verified by tools/self-verify).
RUNTIME="$ROOT/android/app/src/main/java/com/voxbridge/runtime"
SRC=""
for f in ChainedTranslator DirectionalTranslator MelSpectrogram ModelProfile ModelPaths ModelInstaller \
         OnnxTranslator SileroVad SpmTokenizer WhisperAsr WhisperBpeDecoder; do
  SRC="$SRC $RUNTIME/$f.kt"
done

STDLIB="$CACHE/kotlinc-$KOTLIN_VER/lib/kotlin-stdlib.jar"
run_suite() { # run_suite <main-class> <test-files...>
  local main="$1"; shift
  local out="$CACHE/classes-${main}"
  rm -rf "$out"
  echo "== kotlinc $KOTLIN_VER (language-version 1.9): $main =="
  # shellcheck disable=SC2086
  "$KOTLINC" -language-version 1.9 $SRC "$HERE"/src/*.kt "$@" -cp "$CP" -d "$out" 2>"$CACHE/kotlinc-$main.log" || {
    /usr/bin/grep -v "^warning:" "$CACHE/kotlinc-$main.log" || true
    echo "COMPILATION FAILED (full log: $CACHE/kotlinc-$main.log)" >&2; exit 1
  }
  echo "== java: $main =="
  java -cp "$out:$STDLIB:$CP" "$main"
}

case "$SUITE" in
  --main)      run_suite TestMainKt "$HERE"/test/TestMain.kt "$HERE"/test/TestOptimum.kt "$HERE"/test/TestTranslator.kt ;;
  --installer) run_suite TestInstallerKt "$HERE"/test/TestInstaller.kt ;;
  --all)       run_suite TestMainKt "$HERE"/test/TestMain.kt "$HERE"/test/TestOptimum.kt "$HERE"/test/TestTranslator.kt \
                    && run_suite TestInstallerKt "$HERE"/test/TestInstaller.kt ;;
  *) echo "usage: $0 [--main|--installer]" >&2; exit 2 ;;
esac
