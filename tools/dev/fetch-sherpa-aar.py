#!/usr/bin/env python3
"""Install the pinned Piper Android AAR without committing the binary."""

from __future__ import annotations

import hashlib
from pathlib import Path
import sys
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[2]
NAME = "sherpa-onnx-static-link-onnxruntime-1.13.3.aar"
DEST = ROOT / "android" / "app" / "libs" / NAME
URL = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.3/{NAME}"
SHA256 = "9f065fe6f2cab09fd48eaa580097293e077637ad53a5e89c5c58a36509386ac7"


def verify(path: Path) -> bool:
    if not path.is_file():
        return False
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != SHA256:
        raise SystemExit(f"AAR hash mismatch at {path}: {digest}; expected {SHA256}")
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
    if "jni/arm64-v8a/libsherpa-onnx-jni.so" not in names:
        raise SystemExit("AAR lacks arm64 sherpa JNI")
    if "jni/arm64-v8a/libonnxruntime.so" in names:
        raise SystemExit("AAR contains a conflicting arm64 dynamic ONNX Runtime")
    return True


def main() -> int:
    if verify(DEST):
        print(f"OK: pinned AAR already installed: {DEST}")
        return 0
    DEST.parent.mkdir(parents=True, exist_ok=True)
    temporary = DEST.with_suffix(".aar.part")
    try:
        print(f"Fetching {URL}")
        with urllib.request.urlopen(URL, timeout=120) as response, temporary.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
        verify(temporary)
        temporary.replace(DEST)
    finally:
        temporary.unlink(missing_ok=True)
    print(f"OK: {DEST}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
