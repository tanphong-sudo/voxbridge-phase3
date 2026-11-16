#!/usr/bin/env bash
# =============================================================================
# VoxBridge — JVM end-to-end harness (works on Colab AND any Linux/macOS)
#
# Compiles the REAL app Kotlin (core/ + runtime/) with the real ONNX Runtime
# Java bundle, then runs tools/e2e-jvm/src/Main.kt against the exported models/
# tree — proving the Kotlin decode code and the Python export scripts agree on
# the same graph contracts BEFORE the APK is ever built.
#
# Usage:
#   bash tools/e2e-jvm/run.sh [MODELS_DIR]     # default: <repo>/models
# Env:
#   JAVA_HOME  optional (Colab: /usr/lib/jvm/java-17-openjdk-amd64)
#   VOXBRIDGE_COMPILE_ONLY=1  compile the real core/runtime/harness, then exit before inference
#   VOXBRIDGE_UNIFIED_MANIFEST=/path/to/smoke.jsonl  run the unified WAV->decision sample runner
#   VOXBRIDGE_UNIFIED_ONLY=1  skip legacy component stages and run only the unified runner
#   VOXBRIDGE_UNIFIED_LOG=/path/to/results.jsonl  structured per-sample output
#   VOXBRIDGE_CORPUS_MANIFEST=/path/to/dev.jsonl  run the Task-5 sequential corpus runner
#   VOXBRIDGE_CORPUS_ONLY=1  skip legacy component stages and run only the corpus runner
#   VOXBRIDGE_CORPUS_LOG=/path/to/results.jsonl  Task-5 structured per-sample output
#   VOXBRIDGE_CORPUS_LIMIT=N  optional bounded prefix for pilot/debug runs
#   VOXBRIDGE_CORPUS_IDS=id1,id2  optional exact manifest-id selection
#
# Idempotent: downloads deps once into tools/e2e-jvm/.cache (gitignored).
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
CACHE="$HERE/.cache"
MODELS_DIR="${1:-${MODELS_DIR:-$ROOT/models}}"

KOTLIN_VER="2.0.21"   # compiler runs on JDK 17 AND 21+ (1.9.x crashes on 21+, KT-59062);
                      # -language-version 1.9 below keeps the app's 1.9 semantics
ORT_VER="1.20.0"      # == onnxruntime-android(-qnn) in app/build.gradle.kts
SP4J_VER="1.0.2"      # == io.github.eix128:sentencepiece4j in app/build.gradle.kts
PROTOBUF_VER="4.32.1" # sentencepiece4j runtime dependency
FASTUTIL_VER="8.5.12" # sentencepiece4j runtime dependency

# ---------------------------------------------------------------- helpers
fetch() { # fetch <url> <dest> — atomic: download to "$2.part", mv into place only on success
          # (a partial download must never poison the cache as a "complete" file)
  [ -s "$2" ] && return 0
  echo "downloading $(basename "$2") ..."
  rm -f "$2.part"
  # both branches must verify non-empty content: a 200-with-empty-body or a
  # truncated transfer must never be promoted into the cache
  if curl -fsSL --retry 3 -o "$2.part" "$1" && [ -s "$2.part" ]; then
    mv -f "$2.part" "$2"
  elif wget -qO "$2.part" "$1" && [ -s "$2.part" ]; then
    mv -f "$2.part" "$2"
  else
    rm -f "$2.part"
    echo "ERROR: download failed: $1" >&2
    return 1
  fi
}

# ---------------------------------------------------------------- java
# kotlinc cannot parse modern JDK release strings (21+ crashes even on 2.0.21),
# so when the system JDK is too new we pin a JDK 17 — same as the Colab notebooks.
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
  # java_home can return a NEWER jdk when 17 is absent — verify the major really is 17
  if [ -n "$JDK17" ] && ! "$JDK17/bin/java" -version 2>&1 | head -1 | grep -q 'version "17'; then
    JDK17=""
  fi
  if [ -z "$JDK17" ] || [ ! -x "$JDK17/bin/java" ]; then
    # No local JDK 17 -> fetch Temurin 17 (idempotent, cached)
    echo "system JDK is $JAVA_MAJOR — fetching Temurin 17 (one-time, cached) ..."
    OS=$(uname -s); ARCH=$(uname -m)
    case "$OS/$ARCH" in
      Darwin/arm64)  TJ=mac;   TA=aarch64 ;;
      Darwin/x86_64) TJ=mac;   TA=x64 ;;
      Linux/x86_64)  TJ=linux; TA=x64 ;;
      Linux/aarch64) TJ=linux; TA=aarch64 ;;
      *) echo "ERROR: unsupported platform $OS/$ARCH — install JDK 17 manually" >&2; exit 2 ;;
    esac
    mkdir -p "$CACHE/jdk17"
    fetch "https://api.adoptium.net/v3/binary/latest/17/ga/$TJ/$TA/jdk/hotspot/normal/eclipse" "$CACHE/jdk17.tar.gz"
    tar -xzf "$CACHE/jdk17.tar.gz" -C "$CACHE/jdk17"
    # macOS bundle: .../jdk-17.x/Contents/Home ; linux: .../jdk-17.x/bin/java — prefer Home
    JDK17="$(find "$CACHE/jdk17" -maxdepth 5 -type d -name Home | head -1 || true)"
    [ -z "$JDK17" ] && JDK17="$(find "$CACHE/jdk17" -maxdepth 2 -type d -name 'jdk-17*' | head -1 || true)"
  fi
  [ -n "$JDK17" ] && [ -x "$JDK17/bin/java" ] || { echo "ERROR: no JDK 17 available" >&2; exit 2; }
  export JAVA_HOME="$JDK17"
  export PATH="$JAVA_HOME/bin:$PATH"
  JAVA_BIN="java"
fi
echo "java: $($JAVA_BIN -version 2>&1 | head -1)"

# ---------------------------------------------------------------- deps
mkdir -p "$CACHE"

KOTLINC="$CACHE/kotlinc-$KOTLIN_VER/bin/kotlinc"
if [ ! -x "$KOTLINC" ]; then
  fetch "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VER/kotlin-compiler-$KOTLIN_VER.zip" "$CACHE/kotlinc-$KOTLIN_VER.zip"
  rm -rf "$CACHE/kotlinc-$KOTLIN_VER"
  unzip -q "$CACHE/kotlinc-$KOTLIN_VER.zip" -d "$CACHE/_unz" # zip root: kotlinc/
  mv "$CACHE/_unz/kotlinc" "$CACHE/kotlinc-$KOTLIN_VER"
  rmdir "$CACHE/_unz"
fi

M2="https://repo.maven.apache.org/maven2"
fetch "$M2/com/microsoft/onnxruntime/onnxruntime/$ORT_VER/onnxruntime-$ORT_VER.jar" \
      "$CACHE/onnxruntime-$ORT_VER.jar"
fetch "$M2/org/json/json/20240303/json-20240303.jar" \
      "$CACHE/json-20240303.jar"
fetch "$M2/io/github/eix128/sentencepiece4j/$SP4J_VER/sentencepiece4j-$SP4J_VER.jar" \
      "$CACHE/sentencepiece4j-$SP4J_VER.jar"
fetch "$M2/com/google/protobuf/protobuf-java/$PROTOBUF_VER/protobuf-java-$PROTOBUF_VER.jar" \
      "$CACHE/protobuf-java-$PROTOBUF_VER.jar"
fetch "$M2/it/unimi/dsi/fastutil/$FASTUTIL_VER/fastutil-$FASTUTIL_VER.jar" \
      "$CACHE/fastutil-$FASTUTIL_VER.jar"
fetch "$M2/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar" \
      "$CACHE/slf4j-api-2.0.13.jar"
fetch "$M2/org/slf4j/slf4j-nop/2.0.13/slf4j-nop-2.0.13.jar" \
      "$CACHE/slf4j-nop-2.0.13.jar"

# ---------------------------------------------------------------- models
if [ ! -d "$MODELS_DIR" ]; then
  echo "ERROR: models dir '$MODELS_DIR' not found." >&2
  echo "Build it with model-tools-python/scripts/fetch_all.sh (after installing its requirements)," >&2
  echo "restore it with colab/01_models_pipeline.ipynb, or point this script at VOXBRIDGE_MODEL_CACHE." >&2
  exit 2
fi
echo "models: $MODELS_DIR"

# ---------------------------------------------------------------- compile
# kotlinc does NOT expand jar wildcards — list them explicitly
CP="$(ls "$CACHE"/*.jar | tr '\n' ':')"
CP="${CP%:}"
SRC="
  $ROOT/core/src/main/kotlin
  $ROOT/android/app/src/main/java/com/voxbridge/runtime/MelSpectrogram.kt
  $ROOT/android/app/src/main/java/com/voxbridge/runtime/RealFft400.kt
  $ROOT/android/app/src/main/java/com/voxbridge/runtime/ResourceScope.kt
  $ROOT/android/app/src/main/java/com/voxbridge/runtime/ModelProfile.kt
  $ROOT/android/app/src/main/java/com/voxbridge/runtime/ModelPaths.kt
  $ROOT/android/app/src/main/java/com/voxbridge/runtime/ModelIntegrity.kt
  $ROOT/android/app/src/main/java/com/voxbridge/runtime/OnnxTranslator.kt
  $ROOT/android/app/src/main/java/com/voxbridge/runtime/SileroVad.kt
  $ROOT/android/app/src/main/java/com/voxbridge/runtime/SpmTokenizer.kt
  $ROOT/android/app/src/main/java/com/voxbridge/runtime/WhisperAsr.kt
  $ROOT/android/app/src/main/java/com/voxbridge/runtime/WhisperBpeDecoder.kt
  $ROOT/android/app/src/main/java/com/voxbridge/runtime/DirectionalTranslator.kt
  $ROOT/android/app/src/main/java/com/voxbridge/runtime/ChainedTranslator.kt
  $HERE/src/android/util/Log.kt
  $HERE/src/Main.kt
  $HERE/src/UnifiedRunner.kt
  $HERE/src/CorpusRunner.kt
  $HERE/src/FullAudioRunner.kt
"
echo "== kotlinc $KOTLIN_VER (language-version 1.9): compiling real core/ + runtime/ + e2e harness =="
# stale classes from a previous run would make the [ -d ] check below pass even
# when kotlinc FAILS — always start from a clean output dir
rm -rf "$CACHE/e2e-classes"
KOTLINC_LOG="$CACHE/kotlinc.log"
# kotlinc's exit code must NOT be swallowed by the grep pipeline (pipefail + `|| true`
# would hide a real failure behind grep's status) — capture it explicitly instead
set +e
# shellcheck disable=SC2086
"$KOTLINC" -language-version 1.9 $SRC -cp "$CP" -d "$CACHE/e2e-classes" >"$KOTLINC_LOG" 2>&1
KOTLINC_RC=$?
set -e
grep -v "^warning:" "$KOTLINC_LOG" || true
if [ "$KOTLINC_RC" -ne 0 ]; then
  echo "COMPILATION FAILED (kotlinc exit $KOTLINC_RC — full log: $KOTLINC_LOG)" >&2
  exit 1
fi
# belt-and-braces: kotlinc exited 0, so the output dir must exist
[ -d "$CACHE/e2e-classes" ] || { echo "COMPILATION FAILED (kotlinc exit 0 but no output produced)" >&2; exit 1; }

if [ "${VOXBRIDGE_COMPILE_ONLY:-0}" = "1" ]; then
  echo "COMPILE ONLY PASS: core/runtime/e2e harness compiled; neural stages were not executed"
  exit 0
fi

# ---------------------------------------------------------------- run
echo "== java: running e2e harness =="
STDLIB="$CACHE/kotlinc-$KOTLIN_VER/lib/kotlin-stdlib.jar"
java -Xmx2g -cp "$CACHE/e2e-classes:$STDLIB:$CP" voxbridge.e2e.MainKt "$MODELS_DIR"
