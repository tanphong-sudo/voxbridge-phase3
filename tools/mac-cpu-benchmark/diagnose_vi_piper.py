#!/usr/bin/env python3
"""Focused live diagnostic for the selected Vietnamese Piper voice.

This is not an intelligibility oracle. It catches the concrete Task-4 regression that motivated the
voice change: eSpeak Vietnamese emits phoneme symbols that must exist in the Piper token/embedding
inventory, and sherpa must not drop them during synthesis.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

import tts_bridge


PROBES = {
    "measurement": "mười lăm mi li mét. hai trăm hai mươi vôn. ba phẩy năm bar.",
    "machine_id": "máy A-12.",
    "safety_negation": "không mở van.",
}


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_inventory(models: Path) -> dict[str, int]:
    root, _onnx, tokens, _data_dir = tts_bridge.voice_bundle(models, "vi")
    configs = sorted(root.glob("*.onnx.json"))
    if len(configs) != 1:
        raise RuntimeError(f"expected exactly one Piper JSON config under {root}, found {len(configs)}")
    config = json.loads(configs[0].read_text(encoding="utf-8"))
    num_symbols = int(config["num_symbols"])
    token_ids = []
    for line in tokens.read_text(encoding="utf-8").splitlines():
        if " " not in line:
            continue
        _token, raw_id = line.rsplit(" ", 1)
        token_ids.append(int(raw_id))
    max_token_id = max(token_ids)
    if max_token_id >= num_symbols:
        raise RuntimeError(
            f"token id {max_token_id} is outside model embedding inventory num_symbols={num_symbols}"
        )
    return {
        "token_count": len(token_ids),
        "max_token_id": max_token_id,
        "num_symbols": num_symbols,
    }


def run_probe(models: Path, out_dir: Path, name: str, text: str) -> dict[str, object]:
    wav = out_dir / f"vi_{name}.wav"
    bridge = Path(__file__).with_name("tts_bridge.py")
    proc = subprocess.run(
        [
            sys.executable,
            str(bridge),
            "--models",
            str(models),
            "--lang",
            "vi",
            "--text",
            text,
            "--out",
            str(wav),
        ],
        text=True,
        capture_output=True,
        check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"{name}: synthesis failed rc={proc.returncode}: {proc.stderr.strip()}")
    if "Skip unknown phonemes" in proc.stderr:
        raise RuntimeError(f"{name}: sherpa dropped unsupported phonemes: {proc.stderr.strip()}")
    lines = [line for line in proc.stdout.splitlines() if line.startswith("{")]
    if not lines:
        raise RuntimeError(f"{name}: missing bridge JSON output: {proc.stdout!r}")
    stats = json.loads(lines[-1])
    stats.update({"probe": name, "text": text, "sha256": _sha256(wav)})
    return stats


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models", type=Path, default=Path("models"))
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=Path("reports/mac-cpu/task4-tts/vi-piper-diagnostics"),
    )
    args = parser.parse_args()
    args.out_dir.mkdir(parents=True, exist_ok=True)

    result = {
        "voice": tts_bridge.VOICE_DIR["vi"],
        "inventory": validate_inventory(args.models),
        "probes": [run_probe(args.models, args.out_dir, name, text) for name, text in PROBES.items()],
    }
    print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
