"""Fetch the directly-downloadable models: Silero VAD + Piper voices.

These need only network + huggingface_hub (no torch). Writes to the MODEL_MANIFEST target paths.

    python fetch_models.py            # fetch all legacy/roadmap voices
    python fetch_models.py --phase2   # Phase-2 VI/EN selected set (VAD + VI/EN voices)
    python fetch_models.py --vad      # just VAD
    python fetch_models.py --piper    # just Piper voices

STATUS: the HF mirror of Silero VAD is gated (401 anonymous), so the canonical GitHub raw file
(pin to a release tag) is the primary source, HF is the fallback, and every download is
validated + published atomically — it never leaves a truncated artifact at the path the app
consumes.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools/dev'))
from model_integrity import promote_tree

import paths

try:
    from huggingface_hub import hf_hub_download
except ImportError:
    sys.exit("huggingface_hub missing — run: pip install -r ../requirements.txt")

# Real silero_vad.onnx is ~2.3 MB; anything smaller is an error page, not a model.
MIN_VAD_BYTES = 1_000_000


def _sha256(path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _piper_source_matches(path, expected_sha256: str | None) -> bool:
    if expected_sha256 is None:
        return True
    try:
        metadata = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return False
    return metadata.get("archive_sha256") == expected_sha256


def _validated_vad_bytes(tmp_path) -> bool:
    """Cheap sanity gate for the VAD artifact: size floor + ONNX-protobuf first byte (0x08)."""
    size = os.path.getsize(tmp_path)
    if size < MIN_VAD_BYTES:
        print(f"[vad] candidate rejected: {size} bytes < floor {MIN_VAD_BYTES}")
        return False
    with open(tmp_path, "rb") as f:
        return f.read(1) == b"\x08"


def _publish(tmp_path, dest) -> None:
    """Atomic same-filesystem publish: validate the .part, then os.replace over dest."""
    if not _validated_vad_bytes(tmp_path):
        os.unlink(tmp_path)
        sys.exit(f"[vad] downloaded artifact failed validation — {dest} left untouched")
    os.replace(tmp_path, dest)
    print(f"[vad] -> {dest}  ({os.path.getsize(dest)} bytes)")


def fetch_vad() -> None:
    """Download silero_vad.onnx (GitHub raw release tag first, HF hub fallback)."""
    paths.ensure(paths.VAD)
    dest = paths.VAD / "silero_vad.onnx"
    if dest.exists() and os.path.getsize(dest) >= MIN_VAD_BYTES:
        print(f"[vad] already present -> {dest}")
        return

    # GitHub raw pinned to the v5.1.2 release tag (a moving ref like `master` could drift).
    github_url = paths.SILERO_GITHUB_RAW
    tmp = dest.with_name(dest.name + ".part")
    try:
        print(f"[vad] downloading {github_url}")
        with urllib.request.urlopen(github_url, timeout=60) as resp, open(tmp, "wb") as out:  # noqa: S310 (trusted GitHub host)
            shutil.copyfileobj(resp, out)
        _publish(tmp, dest)
        return
    except Exception as e:
        print(f"[vad] GitHub raw failed ({type(e).__name__}: {e}); falling back to Hugging Face hub ...")
        tmp.unlink(missing_ok=True)

    # HF fallback — the mirror may be gated (401) or reachable depending on environment/token.
    last_err: Exception | None = None
    for candidate in ("src/silero_vad/data/silero_vad.onnx", "files/silero_vad.onnx", "silero_vad.onnx"):
        try:
            f = hf_hub_download(repo_id=paths.HF_SILERO, filename=candidate)
        except Exception as e:
            last_err = e
            print(f"[vad] HF candidate {candidate!r} failed: {type(e).__name__}: {e}")
            continue
        shutil.copyfile(f, tmp)
        _publish(tmp, dest)
        return
    reason = f"{type(last_err).__name__}: {last_err}" if last_err else "no candidate succeeded"
    sys.exit(f"[vad] silero_vad.onnx not downloadable ({reason}). "
             f"Download it manually and place it at {dest}")


def _fetch_piper(bundle: str, label: str) -> None:
    """Download + extract a sherpa-onnx Piper voice bundle to text_to_speech/piper/<bundle>/.

    The bundle (model.onnx + tokens.txt + espeak-ng-data/) is what sherpa-onnx OfflineTts needs;
    the bare .onnx from rhasspy/piper-voices lacks tokens/espeak and will NOT work.

    ATOMICITY (Round-4 review MED): extraction lands in a temp dir and is promoted with
    os.replace() — a run killed mid-extract (Colab preemption, Ctrl-C; espeak-ng-data alone is
    hundreds of files) leaves a *.partial-* dir that the completeness check rejects and the
    next run prunes, never a broken voice at the path the app (and notebook 02's gate)
    consumes.
    """
    paths.ensure(paths.TTS_PIPER)
    dest_dir = paths.TTS_PIPER / bundle
    source_record = dest_dir / "source.json"
    expected_sha256 = paths.PIPER_ARCHIVE_SHA256.get(bundle)
    complete = (
        dest_dir.exists()
        and any(dest_dir.glob("*.onnx"))
        and (dest_dir / "tokens.txt").exists()
        and (dest_dir / "espeak-ng-data").is_dir()
        and (dest_dir / "MODEL_CARD").is_file()
        and _piper_source_matches(source_record, expected_sha256)
    )
    if complete:
        print(f"[piper:{label}] already present -> {dest_dir}")
        return
    # Prune stale partials from interrupted runs (none of them can pass the check above, so
    # removing them is always safe).
    for stale in paths.TTS_PIPER.glob(f"{bundle}.partial-*"):
        shutil.rmtree(stale, ignore_errors=True)
    url = f"{paths.SHERPA_TTS_RELEASE}/{bundle}.tar.bz2"
    print(f"[piper:{label}] downloading {url}")
    staging = paths.TTS_PIPER / f"{bundle}.partial-{os.getpid()}"
    with tempfile.NamedTemporaryFile(suffix=".tar.bz2", delete=False) as tmp:
        try:
            with urllib.request.urlopen(url, timeout=120) as resp, open(tmp.name, "wb") as out:  # noqa: S310 (trusted GitHub release host)
                shutil.copyfileobj(resp, out)
            archive_sha256 = _sha256(tmp.name)
            if expected_sha256 is not None and archive_sha256 != expected_sha256:
                sys.exit(
                    f"[piper:{label}] archive SHA-256 mismatch for {bundle}: "
                    f"expected {expected_sha256}, got {archive_sha256}"
                )
            # Extract to a private temp dir INSIDE TTS_PIPER (same filesystem -> atomic rename),
            # then validate and promote with rollback; the old good tree survives failure.
            shutil.rmtree(staging, ignore_errors=True)
            with tarfile.open(tmp.name, "r:bz2") as tar:
                # Fail closed on runtimes without safe extraction filters.
                tar.extractall(staging, filter="data")
            extracted = staging / bundle
            if not extracted.is_dir():
                # Defensive: a bundle that stops shipping a matching top-level dir surfaces here
                # instead of silently promoting a mis-shaped tree.
                sys.exit(f"[piper:{label}] archive layout changed: expected top-level {bundle}/")
            if not (extracted / "MODEL_CARD").is_file() or not (extracted / "tokens.txt").is_file() or not any(extracted.glob('*.onnx')) or not (extracted / 'espeak-ng-data').is_dir():
                sys.exit(f"[piper:{label}] incomplete staged bundle: {extracted}")
            if expected_sha256 is not None:
                (extracted / source_record.name).write_text(json.dumps({
                    "bundle": bundle,
                    "release_url": url,
                    "archive_sha256": archive_sha256,
                    "upstream": "https://huggingface.co/rhasspy/piper-voices/tree/main/vi/vi_VN/vais1000/medium",
                    "dataset_license": "CC BY 4.0",
                }, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            promote_tree(extracted, dest_dir)
        finally:
            shutil.rmtree(staging, ignore_errors=True)
            if os.path.exists(tmp.name):
                os.unlink(tmp.name)
    onnx = next(iter(dest_dir.glob("*.onnx")), None)
    if onnx is None:
        sys.exit(f"[piper:{label}] extracted but no .onnx under {dest_dir}")
    print(f"[piper:{label}] -> {dest_dir}  (model: {onnx.name})")


def fetch_piper(languages=("vi", "en", "zh", "ko")) -> None:
    """Download the requested voice bundles; the no-arg form preserves the legacy full set."""
    bundles = {
        "vi": paths.PIPER_VI_BUNDLE,
        "en": paths.PIPER_EN_BUNDLE,
        "zh": paths.PIPER_ZH_BUNDLE,
        "ko": paths.PIPER_KO_BUNDLE,
    }
    for language in languages:
        _fetch_piper(bundles[language], language)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--vad", action="store_true")
    ap.add_argument("--piper", action="store_true")
    ap.add_argument(
        "--phase2",
        action="store_true",
        help="fetch exactly the final Phase-2 VI/EN directly-downloadable set",
    )
    args = ap.parse_args()
    if args.phase2:
        fetch_vad()
        fetch_piper(("vi", "en"))
        print("[fetch_models] Phase-2 VI/EN set done.")
        return
    do_all = not (args.vad or args.piper)
    if args.vad or do_all:
        fetch_vad()
    if args.piper or do_all:
        fetch_piper()
    print("[fetch_models] done.")


if __name__ == "__main__":
    main()
