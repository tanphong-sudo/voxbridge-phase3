#!/usr/bin/env python3
"""Sync one manifest-selected VoxBridge model profile to one adb device."""
from __future__ import annotations

import argparse
import glob
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import shlex
import re
sys.path.insert(0, str(Path(__file__).resolve().parent))
from model_integrity import DigestCache, integrity_text, payload_file, sha256_file

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.voxbridge"
ACTIVITY = "com.voxbridge.ui.MainActivity"
TREE_MARKER = ".voxbridge-sync-sha256"


def run(cmd: list[str], *, capture: bool = False, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(cmd, text=True, capture_output=capture, check=check)


def adb_path() -> str:
    candidates = [
        os.environ.get("ADB"),
        shutil.which("adb"),
        str(Path.home() / "Library/Android/sdk/platform-tools/adb"),
    ]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return candidate
    raise SystemExit("FAIL: adb not found. Install Android SDK Platform-Tools or set ADB=/path/to/adb")


def manifest_path(profile: str) -> Path:
    path = ROOT / "config/models" / f"dev-{profile}.json"
    if not path.is_file():
        choices = sorted(p.stem.removeprefix("dev-") for p in (ROOT / "config/models").glob("dev-*.json"))
        raise SystemExit(f"FAIL: unknown profile {profile!r}. Available: {', '.join(choices)}")
    return path


def load_manifest(profile: str) -> dict:
    data = json.loads(manifest_path(profile).read_text())
    if data.get("schemaVersion") != 1 or data.get("profile") != profile:
        raise SystemExit(f"FAIL: invalid manifest contract: {manifest_path(profile)}")
    return data


def drive_candidates() -> list[Path]:
    out: list[Path] = []
    explicit = os.environ.get("VOXBRIDGE_MODEL_CACHE")
    if explicit:
        out.append(Path(explicit).expanduser())
    for pattern in (
        "~/Library/CloudStorage/GoogleDrive-*/My Drive/VoxBridge/models",
        "~/Library/CloudStorage/GoogleDrive-*/MyDrive/VoxBridge/models",
        "~/Google Drive/My Drive/VoxBridge/models",
    ):
        out.extend(Path(p) for p in glob.glob(os.path.expanduser(pattern)))
    out.append(ROOT / "models")
    return out


def artifact_missing(cache: Path, artifact: dict) -> list[str]:
    path = cache / artifact["path"]
    if artifact["kind"] == "file":
        return [] if path.is_file() and path.stat().st_size > 0 else [artifact["path"]]
    if not path.is_dir():
        return [artifact["path"] + "/"]
    missing: list[str] = []
    for rel in artifact.get("requiredFiles", []):
        if not (path / rel).is_file():
            missing.append(f"{artifact['path']}/{rel}")
    for rel in artifact.get("requiredDirs", []):
        if not (path / rel).is_dir():
            missing.append(f"{artifact['path']}/{rel}/")
    for pattern in artifact.get("requiredGlobs", []):
        if not any(p.is_file() for p in path.glob(pattern)):
            missing.append(f"{artifact['path']}/{pattern}")
    any_of = artifact.get("anyOfFiles", [])
    if any_of and not any((path / rel).is_file() for rel in any_of):
        missing.append(f"{artifact['path']}/({' or '.join(any_of)})")
    if not any(p.is_file() for p in path.rglob("*")):
        missing.append(artifact["path"] + "/<non-empty>")
    return missing


def cache_complete(cache: Path, manifest: dict) -> bool:
    for artifact in manifest["artifacts"]:
        if artifact_missing(cache, artifact):
            return False
    return True


def find_cache(manifest: dict) -> Path | None:
    for candidate in drive_candidates():
        if cache_complete(candidate, manifest):
            return candidate
    return None


def materialize_with_rclone(manifest: dict, profile: str) -> Path | None:
    remote = os.environ.get("VOXBRIDGE_RCLONE_REMOTE")
    rclone = shutil.which("rclone")
    if not remote or not rclone:
        return None
    cache = Path.home() / ".cache/voxbridge/models" / profile
    cache.mkdir(parents=True, exist_ok=True)
    base = remote.rstrip("/")
    for artifact in manifest["artifacts"]:
        rel = artifact["path"]
        dest = cache / rel
        if artifact["kind"] == "file":
            dest.parent.mkdir(parents=True, exist_ok=True)
            run([rclone, "copyto", f"{base}/{rel}", str(dest), "--size-only"])
        else:
            dest.mkdir(parents=True, exist_ok=True)
            run([rclone, "copy", f"{base}/{rel}", str(dest), "--size-only"])
    return cache if cache_complete(cache, manifest) else None


def selected_files(cache: Path, manifest: dict) -> list[tuple[Path, str]]:
    files: dict[str, Path] = {}
    for artifact in manifest["artifacts"]:
        rel = artifact["path"]
        path = cache / rel
        expected = artifact.get("expectedBytes")
        missing = artifact_missing(cache, artifact)
        if missing:
            raise SystemExit("FAIL: missing selected model artifacts:\n - " + "\n - ".join(missing))
        if artifact["kind"] == "file":
            if expected is not None and path.stat().st_size != expected:
                raise SystemExit(f"FAIL: size mismatch for {path}: {path.stat().st_size} != {expected}")
            files[rel] = path
        else:
            if expected is not None:
                actual = sum(p.stat().st_size for p in path.rglob("*") if p.is_file())
                if actual != expected:
                    raise SystemExit(f"FAIL: size mismatch for {path}: {actual} != {expected}")
            for child in path.rglob("*"):
                if payload_file(child, cache):
                    child_rel = child.relative_to(cache).as_posix()
                    files[child_rel] = child
    return sorted(((path, rel) for rel, path in files.items()), key=lambda pair: pair[1])


def tree_files(root: Path) -> list[Path]:
    return sorted(
        (p for p in root.rglob("*") if payload_file(p, root)),
        key=lambda p: p.relative_to(root).as_posix(),
    )


def tree_fingerprint(root: Path, cache=None) -> str:
    """Content fingerprint used to skip a whole already-synced model tree in one adb read."""
    digest = hashlib.sha256()
    for path in tree_files(root):
        rel = path.relative_to(root).as_posix().encode()
        digest.update(rel)
        digest.update(b"\0")
        digest.update(str(path.stat().st_size).encode())
        digest.update(b"\0")
        digest.update((cache.digest(path) if cache else sha256_file(path)).encode())
        digest.update(b"\0")
    return digest.hexdigest()


def one_device(adb: str) -> None:
    output = run([adb, "devices"], capture=True).stdout.splitlines()
    devices = [line for line in output[1:] if "\tdevice" in line]
    if len(devices) != 1:
        raise SystemExit(f"FAIL: expected exactly one authorized adb device, got {len(devices)}")


def validate_device_model_root(root: str) -> str:
    # Sync removes narrowly scoped staging/backup trees. Never trust an arbitrary log path.
    if not re.fullmatch(r"/storage/emulated/[0-9]+/Android/data/com\.voxbridge/files/models", root):
        raise SystemExit("FAIL: unexpected device model root; refusing writes: " + root)
    return root


def device_model_root(adb: str) -> str:
    package = run([adb, "shell", "pm", "path", PACKAGE], capture=True, check=False).stdout.strip()
    if not package.startswith("package:"):
        raise SystemExit("FAIL: com.voxbridge is not installed. Run ./tools/dev/run-device.sh --no-logcat once first.")
    run([adb, "logcat", "-c"], check=False)
    # Force a fresh Activity/process in the same ActivityManager transaction. A separate
    # force-stop followed by a normal start can race the current top activity after adb install -r:
    # Android may report "intent has been delivered to currently running top-most instance", in
    # which case the retained ViewModel does not emit MODEL_ROOT again and discovery times out.
    run(
        [adb, "shell", "am", "start", "-S", "-W", "-n", f"{PACKAGE}/{ACTIVITY}"],
        check=False,
    )
    deadline = time.time() + 15
    while time.time() < deadline:
        logs = run([adb, "logcat", "-d", "-v", "brief", "-s", "ModelRepository:I", "*:S"], capture=True, check=False).stdout
        for line in logs.splitlines():
            marker = "MODEL_ROOT="
            if marker in line:
                return validate_device_model_root(line.split(marker, 1)[1].strip())
        time.sleep(0.5)
    raise SystemExit("FAIL: app did not report MODEL_ROOT. Open debugCpu once and inspect ModelRepository in Logcat.")


def remote_size(adb: str, path: str) -> int | None:
    # Android 11+ external storage is FUSE-backed. `run-as <package>` can see the app-specific
    # external-files path but SELinux may deny stat/mkdir there (confirmed on Android 14), while
    # the adb shell domain has ext_data_rw and can write /Android/data/<package>/files. Files
    # written by shell remain readable by the actual app process through the app-specific FUSE
    # view. Use shell for transport/verification; the app still owns model discovery/readiness.
    cp = run([adb, "shell", "stat", "-c", "%s", path], capture=True, check=False)
    try:
        return int(cp.stdout.strip()) if cp.returncode == 0 else None
    except ValueError:
        return None


def remote_text(adb: str, path: str) -> str | None:
    cp = run([adb, "shell", "cat", path], capture=True, check=False)
    return cp.stdout.strip() if cp.returncode == 0 else None


def remote_digest(adb: str, path: str) -> str | None:
    cp = run([adb, "shell", "sha256sum " + shlex.quote(path)], capture=True, check=False)
    return cp.stdout.split()[0] if cp.returncode == 0 and cp.stdout.split() else None


def verify_remote_tree(adb: str, local: Path, remote: str, cache=None):
    # One shell call, streaming sha256sum; filenames quoted, no host shell expansion.
    files = tree_files(local)
    for offset in range(0, len(files), 32):
        batch = files[offset:offset + 32]
        command = " && ".join("sha256sum " + shlex.quote(remote + '/' + p.relative_to(local).as_posix()) for p in batch)
        cp = run([adb, "shell", command], capture=True, check=False)
        actual = [line.split()[0] for line in cp.stdout.splitlines() if line.split()]
        expected = [cache.digest(p) if cache else sha256_file(p) for p in batch]
        if cp.returncode or actual != expected:
            raise SystemExit("FAIL: device content SHA-256 mismatch: " + remote)


def push_file_atomic(adb: str, local: Path, remote: str) -> int:
    size = local.stat().st_size
    digest = sha256_file(local)
    if remote_size(adb, remote) == size and remote_digest(adb, remote) == digest:
        print(f"SKIP {local.name} ({size:,} bytes)")
        return 0
    parent = remote.rsplit("/", 1)[0]
    run([adb, "shell", "mkdir", "-p", parent])
    temp = remote + ".part"
    cp = subprocess.run([adb, "push", str(local), temp])
    if cp.returncode != 0:
        raise SystemExit(f"FAIL: adb transfer failed for {local}")
    if remote_digest(adb, temp) != digest:
        raise SystemExit(f"FAIL: content mismatch in staging: {remote}; previous file preserved")
    run([adb, "shell", "mv", "-f", temp, remote])
    final = remote_size(adb, remote)
    if final != size:
        raise SystemExit(f"FAIL: verification mismatch after push: {remote}: device={final}, local={size}")
    return size


def push_tree_atomic(adb: str, local: Path, remote: str, *, verify_content=False, cache=None) -> tuple[int, int]:
    """Stream one selected model tree as tar, then swap it into place.

    Android 11+ external storage is FUSE-backed. Recursive `adb push` tries to create child
    directories through adb's secure_mkdirs path and is denied under /Android/data on Android 14.
    The shell domain itself has ext_data_rw, so we pre-create one staging directory and let
    Android's tar extract the complete tree inside it. Python tarfile avoids macOS AppleDouble
    `._*` metadata. A content marker makes subsequent syncs O(1) per tree.
    """
    recover_remote_tree(adb, remote)
    files = tree_files(local)
    fingerprint = tree_fingerprint(local, cache)
    marker = f"{remote.rstrip('/')}/{TREE_MARKER}"
    marker_matches = remote_text(adb, marker) == fingerprint
    if marker_matches:
        if verify_content:
            try:
                verify_remote_tree(adb, local, remote, cache)
            except SystemExit:
                print(f'REPAIR content mismatch despite marker: {remote}')
                marker_matches = False
    if marker_matches:
        bytes_total = sum(p.stat().st_size for p in files)
        print(f"SKIP TREE {local.name} ({len(files)} files, {bytes_total:,} bytes)")
        return 0, len(files)

    parent = remote.rsplit("/", 1)[0]
    staging = remote + ".part-tree"
    previous = remote + ".previous-tree"
    run([adb, "shell", "mkdir", "-p", parent])
    run([adb, "shell", "rm", "-rf", staging], check=False)
    run([adb, "shell", "mkdir", "-p", staging])

    proc = subprocess.Popen(
        [adb, "shell", "tar", "-xf", "-", "-C", staging],
        stdin=subprocess.PIPE,
    )
    if proc.stdin is None:
        proc.kill()
        raise SystemExit(f"FAIL: could not open adb tar stream for {local}")
    try:
        with tarfile.open(fileobj=proc.stdin, mode="w|") as archive:
            # Add explicit directories first, then files; recursive=False prevents duplicate walks.
            entries = sorted(
                (p for p in local.rglob("*") if p.is_dir() or payload_file(p, local)),
                key=lambda p: p.relative_to(local).as_posix(),
            )
            for path in entries:
                archive.add(path, arcname=path.relative_to(local).as_posix(), recursive=False)
        proc.stdin.close()
    except BrokenPipeError:
        proc.kill()
        proc.wait()
        raise SystemExit(f"FAIL: device tar extraction aborted for {local}")
    rc = proc.wait()
    if rc != 0:
        raise SystemExit(f"FAIL: device tar extraction failed for {local} (exit {rc})")

    # Always verify newly transferred bytes before marker publication or promotion.
    verify_remote_tree(adb, local, staging, cache)
    with tempfile.NamedTemporaryFile("w", delete=False) as marker_file:
        marker_file.write(fingerprint + "\n")
        marker_local = Path(marker_file.name)
    try:
        cp = subprocess.run([adb, "push", str(marker_local), f"{staging}/{TREE_MARKER}"])
        if cp.returncode != 0:
            raise SystemExit(f"FAIL: could not publish tree marker for {local}")
    finally:
        marker_local.unlink(missing_ok=True)

    # App is force-stopped during sync, so a two-rename swap cannot race a live model reader.
    promote_remote_tree(adb, staging, remote)

    if remote_text(adb, marker) != fingerprint:
        raise SystemExit(f"FAIL: tree marker verification failed after publish: {remote}")
    bytes_total = sum(p.stat().st_size for p in files)
    print(f"PUSH TREE {local.name} ({len(files)} files, {bytes_total:,} bytes)")
    return bytes_total, 0


def promote_remote_tree(adb: str, staging: str, remote: str):
    previous = remote + '.previous-tree'
    if run([adb, "shell", "test", "-e", previous], check=False).returncode == 0:
        raise SystemExit(f"FAIL: recovery backup already exists: {previous}; inspect before retry")
    existed = run([adb, "shell", "test", "-e", remote], check=False).returncode == 0
    if existed:
        run([adb, "shell", "mv", remote, previous])
    try:
        run([adb, "shell", "mv", staging, remote])
    except subprocess.CalledProcessError:
        if existed:
            run([adb, "shell", "mv", previous, remote])
        raise
    run([adb, "shell", "rm", "-rf", previous], check=False)

def recover_remote_tree(adb: str, remote: str):
    """Resume interrupted publication without destroying its last good tree."""
    previous = remote + '.previous-tree'
    if run([adb, 'shell', 'test', '-e', previous], check=False).returncode != 0:
        return
    if run([adb, 'shell', 'test', '-e', remote], check=False).returncode != 0:
        run([adb, 'shell', 'mv', previous, remote])
        print('RECOVER restored previous model tree: ' + remote)
    else:
        # Both copies exist: may be a completed swap before backup cleanup. Do not
        # choose/delete either automatically without verifying its full content.
        raise SystemExit(f'FAIL: interrupted publication has both {remote} and {previous}; inspect before retry')


def sync(adb: str, cache: Path, manifest: dict, *, verify_content=False, digest_cache=None) -> None:
    root = device_model_root(adb)
    files = selected_files(cache, manifest)  # local completeness + expected-size gate
    # Never mutate a model tree under live ORT/sherpa sessions. accept-device.sh relaunches after
    # sync; standalone sync leaves the app stopped so the next Run starts from a coherent set.
    run([adb, "shell", "am", "force-stop", PACKAGE], check=False)
    transferred = 0
    skipped = 0
    print(f"profile: {manifest['profile']}")
    print(f"cache:   {cache}")
    print(f"device:  {root}")
    for artifact in manifest["artifacts"]:
        rel = artifact["path"]
        local = cache / rel
        remote = f"{root.rstrip('/')}/{rel}"
        if artifact["kind"] == "file":
            size = local.stat().st_size
            sent = push_file_atomic(adb, local, remote)
            transferred += sent
            if sent == 0:
                skipped += 1
            else:
                print(f"PUSH {rel} ({size:,} bytes)")
        else:
            sent, skipped_tree = push_tree_atomic(adb, local, remote, verify_content=verify_content, cache=digest_cache)
            transferred += sent
            skipped += skipped_tree
    with tempfile.NamedTemporaryFile('w', delete=False) as contract:
        contract.write(integrity_text(files, digest_cache))
        contract_path = Path(contract.name)
    try:
        push_file_atomic(adb, contract_path, root.rstrip('/') + '/integrity.sha256')
    finally:
        contract_path.unlink(missing_ok=True)
    print(f"Transferred: {transferred:,} bytes")
    print(f"Skipped unchanged: {skipped} files")
    print(f"Selected/manifest-checked files: {len(files)}")
    print('Device verification: ' + ('all payload SHA-256' if verify_content else 'new transfers SHA-256; unchanged trees use prior content marker'))


def stamp_sizes(cache: Path, profile: str, manifest: dict) -> None:
    for artifact in manifest["artifacts"]:
        path = cache / artifact["path"]
        if artifact["kind"] == "file":
            artifact["expectedBytes"] = path.stat().st_size
        else:
            artifact["expectedBytes"] = sum(p.stat().st_size for p in path.rglob("*") if p.is_file())
    target = manifest_path(profile)
    target.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
    print(f"Stamped exact cache sizes into {target}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("profile", nargs="?", default="vi-en-phase2")
    parser.add_argument("--stamp-sizes", action="store_true")
    parser.add_argument("--verify-content", action="store_true", help="Rehash all selected files on device, including skipped trees")
    parser.add_argument("--verify-full", action="store_true", help="Bypass host digest cache and verify all device contents")
    parser.add_argument("--trust-metadata-cache", action="store_true", help="Opt in to host digest cache; metadata is not a content-integrity proof")
    args = parser.parse_args()
    manifest = load_manifest(args.profile)
    cache = find_cache(manifest) or materialize_with_rclone(manifest, args.profile)
    if cache is None:
        raise SystemExit(
            "FAIL: selected model profile is not materialized.\n"
            "Set VOXBRIDGE_MODEL_CACHE=/path/to/Google Drive/VoxBridge/models, or configure rclone and set\n"
            "VOXBRIDGE_RCLONE_REMOTE='gdrive:VoxBridge/models'. Only the selected manifest is copied."
        )
    if args.stamp_sizes:
        stamp_sizes(cache, args.profile, manifest)
        return
    adb = adb_path()
    one_device(adb)
    digest_cache = DigestCache(Path.home() / '.cache/voxbridge/sync-digests.json',
                               enabled=args.trust_metadata_cache and not args.verify_full)
    sync(adb, cache, manifest, verify_content=args.verify_content or args.verify_full, digest_cache=digest_cache)
    digest_cache.save()


if __name__ == "__main__":
    main()
