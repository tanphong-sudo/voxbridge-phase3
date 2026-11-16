#!/usr/bin/env bash
# Restore the Drive model cache atomically into a Colab checkout.
set -euo pipefail

CACHE_DIR="${1:-/content/drive/MyDrive/VoxBridge/models}"
TARGET_DIR="${2:-models}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ ! -d "$CACHE_DIR" ]]; then
  printf 'Model cache directory does not exist: %s\n' "$CACHE_DIR" >&2
  printf 'Expected Google Drive path: /content/drive/MyDrive/VoxBridge/models\n' >&2
  exit 1
fi
CACHE_DIR="$(cd "$CACHE_DIR" && pwd)"
TARGET_PARENT="$(cd "$(dirname "$TARGET_DIR")" && pwd)"
TARGET_NAME="$(basename "$TARGET_DIR")"
STAGING="$TARGET_PARENT/.${TARGET_NAME}.restore.$$"
BACKUP="$TARGET_PARENT/.${TARGET_NAME}.previous.$$"

test -d "$CACHE_DIR"
test ! -e "$STAGING"
mkdir "$STAGING"
cleanup() { rm -rf "$STAGING"; }
trap cleanup EXIT

# Google Drive FUSE does not reliably support a model-tree symlink. Copying into a staging
# tree lets rsync checksum each file before the checkout is switched to it.
rsync -a --checksum --human-readable "$CACHE_DIR/" "$STAGING/"
bash "$ROOT/tools/dev/status.sh" "$STAGING"

if [[ -e "$TARGET_PARENT/$TARGET_NAME" ]]; then
  mv "$TARGET_PARENT/$TARGET_NAME" "$BACKUP"
fi
mv "$STAGING" "$TARGET_PARENT/$TARGET_NAME"
trap - EXIT
rm -rf "$BACKUP"
printf 'Restored model cache: %s -> %s\n' "$CACHE_DIR" "$TARGET_PARENT/$TARGET_NAME"
