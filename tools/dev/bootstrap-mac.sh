#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CACHE="$HOME/.cache/voxbridge"
JDK_HOME="$CACHE/jdk17/Contents/Home"
GRADLE_VERSION="8.9"

mkdir -p "$CACHE"
if [[ ! -x "$JDK_HOME/bin/java" ]]; then
  echo "Downloading Temurin JDK 17 for local VoxBridge builds..."
  ARCH="$(uname -m)"
  case "$ARCH" in arm64) ADOPTIUM_ARCH=aarch64 ;; x86_64) ADOPTIUM_ARCH=x64 ;; *) echo "Unsupported Mac arch: $ARCH" >&2; exit 2 ;; esac
  TMP="$CACHE/jdk17.tar.gz"
  curl -fL --retry 3 "https://api.adoptium.net/v3/binary/latest/17/ga/mac/$ADOPTIUM_ARCH/jdk/hotspot/normal/eclipse" -o "$TMP"
  rm -rf "$CACHE/jdk17" "$CACHE/jdk-unpack"
  mkdir -p "$CACHE/jdk-unpack"
  tar -xzf "$TMP" -C "$CACHE/jdk-unpack"
  FOUND="$(find "$CACHE/jdk-unpack" -type d -path '*/Contents/Home' | head -1)"
  [[ -n "$FOUND" ]] || { echo "FAIL: downloaded JDK has unexpected layout" >&2; exit 2; }
  mkdir -p "$CACHE/jdk17/Contents"
  mv "$FOUND" "$JDK_HOME"
  rm -rf "$CACHE/jdk-unpack" "$TMP"
fi

export JAVA_HOME="$JDK_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

if [[ ! -x "$ROOT/gradlew" ]]; then
  ZIP="$CACHE/gradle-$GRADLE_VERSION-bin.zip"
  DIST="$CACHE/gradle-$GRADLE_VERSION"
  if [[ ! -x "$DIST/bin/gradle" ]]; then
    curl -fL --retry 3 "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP"
    rm -rf "$DIST"
    unzip -q "$ZIP" -d "$CACHE"
  fi
  (cd "$ROOT" && "$DIST/bin/gradle" wrapper --gradle-version "$GRADLE_VERSION" --distribution-type bin)
fi

printf 'JAVA_HOME=%s\n' "$JAVA_HOME"
"$ROOT/gradlew" --version | sed -n '1,14p'
echo "Mac bootstrap complete."
