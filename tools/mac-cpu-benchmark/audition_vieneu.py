#!/usr/bin/env python3
"""Offline candidate audition only: pinned VieNeu FP32, no production integration."""
from __future__ import annotations

import argparse
import hashlib
from importlib.metadata import version
import json
from pathlib import Path
import resource
import time
import urllib.request

from audition_tts import PHRASES

MODEL = ("pnnbao-ump/VieNeu-TTS-v3-Turbo", "61b85e3d937fbbacb387714180e8182823512523")
CODEC = ("OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX", "ceff0d0749bfb3fa2d61149794ec6feef0d1e1ae")
CODEC_FILES = {"moss_audio_tokenizer_decode_full.onnx", "moss_audio_tokenizer_decode_shared.data",
    "moss_audio_tokenizer_decode_step.onnx", "codec_browser_onnx_meta.json"}


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest()


def download(artifacts: Path) -> None:
    records = []
    for (repo, revision), folder, prefix in [(MODEL, "model", "onnx_update/"), (CODEC, "codec", "")]:
        url = f"https://huggingface.co/api/models/{repo}/revision/{revision}?blobs=true"
        with urllib.request.urlopen(url, timeout=60) as response:
            metadata = json.load(response)
        if metadata["sha"] != revision:
            raise RuntimeError("Model revision mismatch")
        root = artifacts / folder
        root.mkdir(parents=True, exist_ok=True)
        for entry in metadata["siblings"]:
            name = entry["rfilename"]
            if (prefix and not name.startswith(prefix)) or (not prefix and name not in CODEC_FILES):
                continue
            path = root / Path(name).name
            expected_hash = entry.get("lfs", {}).get("sha256")
            valid = path.is_file() and path.stat().st_size == entry["size"]
            if valid and expected_hash:
                valid = digest(path) == expected_hash
            if not valid:
                partial = path.with_suffix(path.suffix + ".part")
                source = f"https://huggingface.co/{repo}/resolve/{revision}/{name}"
                with urllib.request.urlopen(source, timeout=60) as response, partial.open("wb") as stream:
                    while chunk := response.read(1024 * 1024):
                        stream.write(chunk)
                if partial.stat().st_size != entry["size"] or (expected_hash and digest(partial) != expected_hash):
                    raise RuntimeError(f"Artifact verification failed: {name}")
                partial.replace(path)
            records.append(dict(repo=repo, revision=revision, file=name, size=entry["size"], sha256=digest(path)))
    (artifacts / "manifest.json").write_text(json.dumps(records, indent=2))


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--artifacts", type=Path, required=True)
    p.add_argument("--out-dir", type=Path, required=True)
    p.add_argument("--download", action="store_true", help="Fetch only pinned model/decoder files (~521 MB)")
    p.add_argument("--texts", type=Path, help="Optional JSON object of phrase ID -> already-verbalized text")
    p.add_argument("--voice", default="Trúc Ly")
    a = p.parse_args()
    if version("vieneu") != "3.8.3":
        p.error("Use the isolated audition environment with vieneu==3.8.3")
    if a.out_dir.exists():
        p.error("Output directory already exists; preserve earlier evidence")
    artifacts = a.artifacts.resolve()
    if a.download:
        download(artifacts)
    manifest = json.loads((artifacts / "manifest.json").read_text())
    for entry in manifest:
        folder = "model" if entry["repo"] == MODEL[0] else "codec"
        revision = MODEL[1] if folder == "model" else CODEC[1]
        path = artifacts / folder / Path(entry["file"]).name
        if entry["revision"] != revision or path.stat().st_size != entry["size"] or digest(path) != entry["sha256"]:
            raise RuntimeError(f"Local artifact mismatch: {entry['file']}")
    import numpy as np
    import soundfile as sf
    import vieneu
    from vieneu._v3_turbo_engine.onnx_runtime_lite import OnnxV3LiteEngine
    from vieneu_utils.core_utils import strip_encoder_pad_frame
    voices_path = Path(vieneu.__file__).parent / "assets/voices_v3_turbo.json"
    voices = json.loads(voices_path.read_text())["presets"]
    v = voices[a.voice]
    conditioning = dict(speaker_emb=np.asarray(v["speaker_emb"], dtype=np.float32),
        ref_codes=strip_encoder_pad_frame(np.asarray(v["codes"], dtype=np.int64)))
    texts = json.loads(a.texts.read_text()) if a.texts else PHRASES
    if not isinstance(texts, dict) or not texts or any(not isinstance(x, str) or not x.strip() for x in texts.values()):
        p.error("Texts must be a nonempty object of phrase IDs to nonblank text")
    started = time.perf_counter()
    engine = OnnxV3LiteEngine(checkpoint_path=str(artifacts / "model"), onnx_dir=str(artifacts / "model"),
        codec_dir=str(artifacts / "codec"), threads=2)
    load_ms = (time.perf_counter() - started) * 1000
    engine.infer(text="Xin chào.", **conditioning)
    out = a.out_dir.resolve()
    out.mkdir(parents=True, exist_ok=False)
    rows = []
    for index, (phrase, text) in enumerate(texts.items()):
        started = time.perf_counter()
        audio = engine.infer(text=text, **conditioning)
        ms = (time.perf_counter() - started) * 1000
        if not len(audio) or not np.all(np.isfinite(audio)):
            raise RuntimeError("Empty or non-finite audio")
        wav = out / f"{index}.wav"
        sf.write(wav, audio, 48000, subtype="PCM_16")
        rows.append(dict(phrase=phrase, text=text, voice=a.voice, synthesis_ms=ms,
            duration_seconds=len(audio) / 48000, rtf=ms / 1000 / (len(audio) / 48000),
            wav=str(wav), sha256=digest(wav), clipping_samples=int(np.sum(np.abs(audio) > 1))))
    summary = dict(scope="Mac CPU full-synthesis audition; not Android, TTFA, MOS or safety acceptance",
        sdk="3.8.3", threads=2, load_ms=load_ms, peak_rss_platform_units=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
        voices_sha256=digest(voices_path), artifacts=manifest, samples=rows, production_changed=False)
    (out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
