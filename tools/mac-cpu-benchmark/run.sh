#!/usr/bin/env bash
set -euo pipefail

START_EPOCH="$(date +%s)"
START_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
OUT=""
UNTRACKED_TMP=""

finish() {
  local rc=$?
  trap - EXIT
  if [ -n "$OUT" ] && [ -f "$OUT/metadata.txt" ]; then
    local end_epoch
    local end_utc
    end_epoch="$(date +%s)"
    end_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    {
      echo "wrapper_end_utc=$end_utc"
      echo "wrapper_end_epoch=$end_epoch"
      echo "wrapper_exit_code=$rc"
      echo "wrapper_duration_seconds=$((end_epoch - START_EPOCH))"
    } >> "$OUT/metadata.txt"
  fi
  [ -z "$UNTRACKED_TMP" ] || rm -f "$UNTRACKED_TMP"
  exit "$rc"
}
trap finish EXIT

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
MODELS="$ROOT/models"
TIER="smoke"
RUN_ID=""
OFFLINE=0
PREFLIGHT_ONLY=0

usage() {
  cat <<'EOF'
Usage: tools/mac-cpu-benchmark/run.sh [options]
  --tier smoke|dev|full
  --models PATH
  --run-id ID
  --offline
  --preflight-only
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --tier) TIER="${2:?missing tier}"; shift 2 ;;
    --models) MODELS="${2:?missing models path}"; shift 2 ;;
    --run-id) RUN_ID="${2:?missing run id}"; shift 2 ;;
    --offline) OFFLINE=1; shift ;;
    --preflight-only) PREFLIGHT_ONLY=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

case "$TIER" in smoke|dev|full) ;; *) echo "invalid tier: $TIER" >&2; exit 2 ;; esac

MODELS="$(cd "$MODELS" 2>/dev/null && pwd -P)" || {
  echo "models directory does not exist: $MODELS" >&2
  exit 2
}
RUN_ID="${RUN_ID:-$(date -u +%Y%m%d-%H%M%S)-${TIER}}"
OUT_CANDIDATE="$ROOT/reports/mac-cpu/$RUN_ID"
[ ! -e "$OUT_CANDIDATE" ] || {
  echo "run output already exists; refusing to overwrite provenance: $OUT_CANDIDATE" >&2
  exit 2
}

HEAD_SHA="$(cd "$ROOT" && git rev-parse HEAD)"
DIFF_SHA256="$(cd "$ROOT" && git diff --binary | shasum -a 256 | awk '{print $1}')"
UNTRACKED_TMP="$(mktemp "${TMPDIR:-/tmp}/voxbridge-untracked.XXXXXX")"
UNTRACKED_SHA256="$(python3 - "$ROOT" "$UNTRACKED_TMP" <<'PY'
from __future__ import annotations

import hashlib
import os
from pathlib import Path
import subprocess
import sys

root = Path(sys.argv[1])
destination = Path(sys.argv[2])
raw = subprocess.check_output(
    ["git", "-C", str(root), "ls-files", "--others", "--exclude-standard", "-z"]
)
relative_paths = sorted(item.decode("utf-8", "surrogateescape") for item in raw.split(b"\0") if item)
canonical = hashlib.sha256()

with destination.open("w", encoding="utf-8", errors="surrogateescape") as out:
    for rel in relative_paths:
        path = root / rel
        digest = hashlib.sha256()
        if path.is_symlink():
            digest.update(b"symlink\0")
            digest.update(os.readlink(path).encode("utf-8", "surrogateescape"))
        elif path.is_file():
            with path.open("rb") as handle:
                for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                    digest.update(chunk)
        else:
            digest.update(b"unsupported\0")
        hexdigest = digest.hexdigest()
        out.write(f"{hexdigest}  {rel}\n")
        canonical.update(rel.encode("utf-8", "surrogateescape"))
        canonical.update(b"\0")
        canonical.update(hexdigest.encode("ascii"))
        canonical.update(b"\n")

print(canonical.hexdigest())
PY
)"
UNTRACKED_COUNT="$(wc -l < "$UNTRACKED_TMP" | tr -d ' ')"
WORKSPACE_SHA256="$(printf '%s\n%s\n%s\n' "$HEAD_SHA" "$DIFF_SHA256" "$UNTRACKED_SHA256" | shasum -a 256 | awk '{print $1}')"

OUT="$OUT_CANDIDATE"
mkdir -p "$OUT"
cp "$UNTRACKED_TMP" "$OUT/workspace-untracked.sha256"

CONFIG="$ROOT/config/models/dev-vi-en-phase2.json"
python3 "$HERE/validate_models.py" \
  --models "$MODELS" \
  --config "$CONFIG" \
  --hash-output "$OUT/model-files.sha256" | tee "$OUT/model-preflight.txt"

MODEL_TREE_SHA256="$(awk -F= '/^MODEL_TREE_SHA256=/{print $2}' "$OUT/model-preflight.txt")"
PARENT_JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v "$PARENT_JAVA_BIN" >/dev/null 2>&1 || PARENT_JAVA_BIN="java"
JAVA_PARENT_PATH="$(command -v "$PARENT_JAVA_BIN" 2>/dev/null || printf '%s' "$PARENT_JAVA_BIN")"
JAVA_PARENT_VERSION="$("$PARENT_JAVA_BIN" -version 2>&1 | head -1 || true)"
JAVA_PARENT_MAJOR="$(printf '%s\n' "$JAVA_PARENT_VERSION" | sed -E 's/.*version "([0-9]+).*/\1/')"
E2E_JAVA_BIN="$PARENT_JAVA_BIN"
if [ "${JAVA_PARENT_MAJOR:-0}" -ge 21 ] 2>/dev/null; then
  if [ "$(uname -s)" = "Darwin" ] && [ -x /usr/libexec/java_home ]; then
    JDK17="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    if [ -n "$JDK17" ] && [ -x "$JDK17/bin/java" ] && "$JDK17/bin/java" -version 2>&1 | head -1 | grep -q 'version "17'; then
      E2E_JAVA_BIN="$JDK17/bin/java"
    else
      E2E_JAVA_BIN=""
    fi
  else
    JDK17="$(ls -d /usr/lib/jvm/java-17-openjdk-* 2>/dev/null | head -1 || true)"
    if [ -n "$JDK17" ] && [ -x "$JDK17/bin/java" ] && "$JDK17/bin/java" -version 2>&1 | head -1 | grep -q 'version "17'; then
      E2E_JAVA_BIN="$JDK17/bin/java"
    else
      E2E_JAVA_BIN=""
    fi
  fi
fi
if [ -n "$E2E_JAVA_BIN" ]; then
  JAVA_E2E_SELECTED_PATH="$(command -v "$E2E_JAVA_BIN" 2>/dev/null || printf '%s' "$E2E_JAVA_BIN")"
  JAVA_E2E_SELECTED_VERSION="$("$E2E_JAVA_BIN" -version 2>&1 | head -1 || true)"
else
  JAVA_E2E_SELECTED_PATH="deferred"
  JAVA_E2E_SELECTED_VERSION="deferred: e2e runner will resolve/fetch JDK 17"
fi
RAM_BYTES="$(sysctl -n hw.memsize 2>/dev/null || echo unknown)"
CPU_MODEL="$(sysctl -n machdep.cpu.brand_string 2>/dev/null || sysctl -n hw.model 2>/dev/null || true)"
if [ -z "$CPU_MODEL" ] && [ -r /proc/cpuinfo ]; then
  CPU_MODEL="$(awk -F: '/model name/{sub(/^[ \t]+/, "", $2); print $2; exit}' /proc/cpuinfo)"
fi
CPU_MODEL="${CPU_MODEL:-unknown}"
{
  echo "run_id=$RUN_ID"
  echo "tier=$TIER"
  echo "offline=$OFFLINE"
  echo "head=$HEAD_SHA"
  echo "branch=$(cd "$ROOT" && git branch --show-current)"
  echo "diff_sha256=$DIFF_SHA256"
  echo "untracked_sha256=$UNTRACKED_SHA256"
  echo "untracked_file_count=$UNTRACKED_COUNT"
  echo "workspace_sha256=$WORKSPACE_SHA256"
  echo "untracked_manifest=workspace-untracked.sha256"
  echo "model_tree_sha256=$MODEL_TREE_SHA256"
  echo "model_config=$(cd "$ROOT" && shasum -a 256 config/models/dev-vi-en-phase2.json | awk '{print $1}')"
  echo "execution_provider=CPU"
  echo "cpu_model=$CPU_MODEL"
  echo "onnxruntime=1.20.0"
  echo "ort_thread_config=default OrtSession.SessionOptions; no explicit intra-op/inter-op thread counts in WhisperAsr or OnnxTranslator"
  echo "inference_parallelism=sequential; wrapper launches one e2e-jvm process"
  echo "decode_asr=greedy argmax; forced language+transcribe+notimestamps prompt; MEAN_DECODE_LEN=200"
  echo "decode_mt=greedy argmax; max_new_tokens=64; EOS stop; decoder-with-past KV path when present"
  echo "kotlin_compiler=2.0.21"
  echo "kotlin_language_version=1.9"
  echo "java_parent=$JAVA_PARENT_VERSION"
  echo "java_parent_bin=$JAVA_PARENT_PATH"
  echo "java_e2e_selected=$JAVA_E2E_SELECTED_VERSION"
  echo "java_e2e_selected_bin=$JAVA_E2E_SELECTED_PATH"
  echo "uname=$(uname -a)"
  echo "arch=$(uname -m)"
  echo "ram_bytes=$RAM_BYTES"
  echo "wrapper_start_utc=$START_UTC"
  echo "wrapper_start_epoch=$START_EPOCH"
} > "$OUT/metadata.txt"

if [ "$PREFLIGHT_ONLY" -eq 1 ]; then
  echo "PASS preflight-only: $OUT"
  exit 0
fi

if [ "$OFFLINE" -eq 1 ]; then
  export VOXBRIDGE_OFFLINE=1
fi

case "$TIER" in
  smoke)
    corpus="$ROOT/build/real-data/fleurs/sample-5x2.tsv"
    [ -f "$corpus" ] || { echo "smoke corpus missing: $corpus" >&2; exit 2; }
    set +e
    VOXBRIDGE_ASR_CORPUS="$corpus" bash "$ROOT/tools/e2e-jvm/run.sh" "$MODELS" | tee "$OUT/e2e-stage-smoke.log"
    E2E_RC=${PIPESTATUS[0]}
    set -e
    E2E_JAVA_ACTUAL="$(awk -F'java: ' '/^java: /{print $2; exit}' "$OUT/e2e-stage-smoke.log")"
    echo "java_e2e_actual=${E2E_JAVA_ACTUAL:-unknown}" >> "$OUT/metadata.txt"
    [ "$E2E_RC" -eq 0 ] || exit "$E2E_RC"
    ;;
  dev)
    corpus="$ROOT/build/mac-cpu-data/manifests/dev.jsonl"
    [ -f "$corpus" ] || { echo "dev corpus missing: $corpus" >&2; exit 2; }
    CORPUS_SHA256="$(shasum -a 256 "$corpus" | awk '{print $1}')"
    CORPUS_SAMPLES="$(wc -l < "$corpus" | tr -d ' ')"
    {
      echo "corpus_manifest=$corpus"
      echo "corpus_manifest_sha256=$CORPUS_SHA256"
      echo "corpus_samples=$CORPUS_SAMPLES"
      echo "corpus_results=$OUT/results.jsonl"
      echo "corpus_score_summary=$OUT/score-summary.json"
      echo "corpus_failures=$OUT/failures.jsonl"
    } >> "$OUT/metadata.txt"

    set +e
    VOXBRIDGE_CORPUS_MANIFEST="$corpus" \
      VOXBRIDGE_CORPUS_ONLY=1 \
      VOXBRIDGE_CORPUS_LOG="$OUT/results.jsonl" \
      bash "$ROOT/tools/e2e-jvm/run.sh" "$MODELS" | tee "$OUT/e2e-stage-dev.log"
    E2E_RC=${PIPESTATUS[0]}
    set -e
    E2E_JAVA_ACTUAL="$(awk -F'java: ' '/^java: /{print $2; exit}' "$OUT/e2e-stage-dev.log")"
    echo "java_e2e_actual=${E2E_JAVA_ACTUAL:-unknown}" >> "$OUT/metadata.txt"
    [ "$E2E_RC" -eq 0 ] || exit "$E2E_RC"

    python3 "$HERE/score.py" \
      --manifest "$corpus" \
      --results "$OUT/results.jsonl" \
      --output "$OUT/score-summary.json" \
      --failures "$OUT/failures.jsonl" | tee "$OUT/score-output.json"
    ;;
  full)
    echo "tier 'full' requires the holdout/full-pipeline runner added by later plan tasks" >&2
    exit 2
    ;;
esac
