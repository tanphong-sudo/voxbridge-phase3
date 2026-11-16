#!/usr/bin/env bash
# Promote a validated local model tree to the Drive cache without overwriting it in place.
set -euo pipefail

SOURCE_DIR="${1:-models}"
CACHE_DIR="${2:-/content/drive/MyDrive/VoxBridge/models}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOURCE_DIR="$(cd "$SOURCE_DIR" && pwd)"
CACHE_PARENT="$(dirname "$CACHE_DIR")"
CACHE_NAME="$(basename "$CACHE_DIR")"
STAGING="$CACHE_PARENT/.${CACHE_NAME}.staging.$$"
BACKUP="$CACHE_PARENT/.${CACHE_NAME}.previous.$$"

bash "$ROOT/tools/dev/validate-model-cache.sh" "$SOURCE_DIR"
mkdir -p "$CACHE_PARENT"
test ! -e "$STAGING"
mkdir "$STAGING"
cleanup() { rm -rf "$STAGING"; }
trap cleanup EXIT

rsync -a --checksum --human-readable "$SOURCE_DIR/" "$STAGING/"
bash "$ROOT/tools/dev/validate-model-cache.sh" "$STAGING"
if [[ -e "$CACHE_DIR" ]]; then
  mv "$CACHE_DIR" "$BACKUP"
fi
mv "$STAGING" "$CACHE_DIR"
trap - EXIT
rm -rf "$BACKUP"
printf 'Promoted validated model cache: %s\n' "$CACHE_DIR"
