#!/usr/bin/env bash
# Public-corpus regression for SafetyFrame source-side activation.
# Downloads only the small FLEURS test TSV metadata/transcripts, not the audio archives.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$ROOT/tools/fleurs-regression"
DATA="${VOXBRIDGE_FLEURS_DATA_DIR:-$ROOT/build/real-data/fleurs}"
OUT="$ROOT/build/verify"
mkdir -p "$DATA" "$OUT"

fetch() {
  local lang="$1"
  local out="$DATA/${lang}-test.tsv"
  local url="https://huggingface.co/datasets/google/fleurs/resolve/main/data/${lang}/test.tsv"
  if [[ -s "$out" ]]; then
    echo "REUSE $out"
    return
  fi
  echo "FETCH $url"
  curl -L --fail --retry 3 --silent --show-error "$url" -o "$out.part"
  mv "$out.part" "$out"
}

fetch vi_vn
fetch en_us

echo "[1/3] Compiling core..."
kotlinc $(find "$ROOT/core/src/main/kotlin" -name '*.kt') -include-runtime -d "$OUT/core.jar" 2>/dev/null
echo "[2/3] Compiling FLEURS activation scanner..."
kotlinc -cp "$OUT/core.jar" "$HERE/Scan.kt" -include-runtime -d "$OUT/fleurs-scan.jar" 2>/dev/null
echo "[3/3] Scanning full VI/EN FLEURS test splits..."
java -cp "$OUT/core.jar:$OUT/fleurs-scan.jar" ScanKt \
  "$DATA/vi_vn-test.tsv" "$DATA/en_us-test.tsv" 100000 | tee "$HERE/RESULTS.txt"
