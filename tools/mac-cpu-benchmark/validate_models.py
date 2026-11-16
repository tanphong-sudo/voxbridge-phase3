#!/usr/bin/env python3
"""Validate the locked VI/EN model bundle and optionally hash every artifact."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate(models: Path, config: Path) -> list[str]:
    spec = json.loads(config.read_text(encoding="utf-8"))
    failures: list[str] = []
    for artifact in spec["artifacts"]:
        role = artifact["role"]
        rel = Path(artifact["path"])
        path = models / rel
        kind = artifact["kind"]
        present = path.is_file() if kind == "file" else path.is_dir()
        if not present:
            failures.append(f"{role}: missing {rel}")
            continue
        if kind == "file" and path.stat().st_size == 0:
            failures.append(f"{role}: required file is zero bytes: {rel}")
        expected = artifact.get("expectedBytes")
        if expected is not None and path.is_file() and path.stat().st_size != expected:
            failures.append(
                f"{role}: {rel} has {path.stat().st_size} bytes, expected {expected}"
            )
        if kind != "tree":
            continue
        for name in artifact.get("requiredFiles", []):
            required = path / name
            if not required.is_file():
                failures.append(f"{role}: missing file {rel / name}")
            elif required.stat().st_size == 0:
                failures.append(f"{role}: required file is zero bytes: {rel / name}")
        for name in artifact.get("requiredDirs", []):
            if not (path / name).is_dir():
                failures.append(f"{role}: missing directory {rel / name}")
        for pattern in artifact.get("requiredGlobs", []):
            matches = [candidate for candidate in path.glob(pattern) if candidate.is_file()]
            if not matches:
                failures.append(f"{role}: no match for {rel / pattern}")
            for match in matches:
                if match.stat().st_size == 0:
                    failures.append(
                        f"{role}: required glob match is zero bytes: {match.relative_to(models)}"
                    )
        any_of = artifact.get("anyOfFiles", [])
        if any_of:
            candidates = [path / name for name in any_of]
            nonempty = [candidate for candidate in candidates if candidate.is_file() and candidate.stat().st_size > 0]
            if not nonempty:
                existing = [candidate.name for candidate in candidates if candidate.is_file()]
                if existing:
                    failures.append(f"{role}: all alternatives are zero bytes under {rel}: {existing}")
                else:
                    failures.append(f"{role}: none of {any_of} exists under {rel}")
    return failures


def write_hashes(models: Path, destination: Path) -> str:
    files = sorted(path for path in models.rglob("*") if path.is_file())
    destination.parent.mkdir(parents=True, exist_ok=True)
    canonical = hashlib.sha256()
    with destination.open("w", encoding="utf-8") as out:
        for path in files:
            rel = path.relative_to(models).as_posix()
            digest = sha256_file(path)
            out.write(f"{digest}  {rel}\n")
            canonical.update(rel.encode("utf-8"))
            canonical.update(b"\0")
            canonical.update(digest.encode("ascii"))
            canonical.update(b"\n")
    return canonical.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--models", type=Path, required=True)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--hash-output", type=Path)
    args = parser.parse_args()

    failures = validate(args.models, args.config)
    if failures:
        for failure in failures:
            print(f"FAIL {failure}")
        return 2
    print("PASS model bundle satisfies locked VI/EN manifest")
    if args.hash_output:
        tree_hash = write_hashes(args.models, args.hash_output)
        print(f"MODEL_TREE_SHA256={tree_hash}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
