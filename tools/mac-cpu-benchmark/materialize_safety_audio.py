#!/usr/bin/env python3
"""Materialize the locked safety text corpus as deterministic Piper WAV input.

This is host-only regression data.  The source labels remain exactly as authored in
``testdata/mac-cpu/safety-corpus.jsonl``; in particular this tool never upgrades
``human_verified`` or ``label_status``.
"""

from __future__ import annotations

import argparse
import array
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import wave

from tts_bridge import BACKEND, _load_sherpa, voice_bundle


TARGET_SAMPLE_RATE = 16_000


def _load_rows(path: Path, split: str) -> list[dict]:
    rows: list[dict] = []
    with path.open(encoding="utf-8") as src:
        for line_no, raw in enumerate(src, start=1):
            line = raw.strip()
            if not line:
                continue
            row = json.loads(line)
            if row.get("split") != split:
                continue
            for field in ("id", "source_lang", "target_lang", "reference_source"):
                if not row.get(field):
                    raise ValueError(f"{path}:{line_no}: missing {field}")
            if row["source_lang"] not in {"en", "vi"}:
                raise ValueError(f"{path}:{line_no}: unsupported source_lang={row['source_lang']!r}")
            rows.append(row)
    if not rows:
        raise ValueError(f"no rows selected for split={split!r}")
    ids = [row["id"] for row in rows]
    if len(set(ids)) != len(ids):
        raise ValueError(f"duplicate ids selected for split={split!r}")
    return rows


class PiperPool:
    def __init__(self, models: Path) -> None:
        self.models = models
        self.sherpa = _load_sherpa()
        self._tts: dict[str, object] = {}

    def _runtime(self, lang: str):
        if lang in self._tts:
            return self._tts[lang]
        _root, onnx, tokens, data_dir = voice_bundle(self.models, lang)
        vits = self.sherpa.OfflineTtsVitsModelConfig(
            model=str(onnx),
            tokens=str(tokens),
            data_dir=str(data_dir),
            noise_scale=0.0,
            noise_scale_w=0.0,
            length_scale=1.0,
        )
        config = self.sherpa.OfflineTtsConfig(
            model=self.sherpa.OfflineTtsModelConfig(
                vits=vits,
                num_threads=2,
                provider="cpu",
            )
        )
        runtime = self.sherpa.OfflineTts(config)
        self._tts[lang] = runtime
        return runtime

    def synthesize(self, lang: str, text: str, output: Path) -> None:
        audio = self._runtime(lang).generate(text, sid=0, speed=1.0)
        sample_rate = int(audio.sample_rate)
        samples = [float(value) for value in audio.samples]
        if sample_rate <= 0 or not samples:
            raise RuntimeError(f"Piper returned invalid audio for lang={lang}: rate={sample_rate}, n={len(samples)}")
        pcm = array.array(
            "h",
            (max(-32768, min(32767, int(max(-1.0, min(1.0, value)) * 32767.0))) for value in samples),
        )
        if sys.byteorder != "little":
            pcm.byteswap()
        output.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(output), "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(sample_rate)
            wav.writeframes(pcm.tobytes())


def _resample_16k(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(source),
            "-ac",
            "1",
            "-ar",
            str(TARGET_SAMPLE_RATE),
            "-c:a",
            "pcm_s16le",
            str(destination),
        ],
        check=True,
    )


def _validate_wav(path: Path) -> None:
    with wave.open(str(path), "rb") as wav:
        if wav.getnchannels() != 1:
            raise ValueError(f"expected mono WAV: {path}")
        if wav.getsampwidth() != 2:
            raise ValueError(f"expected PCM16 WAV: {path}")
        if wav.getframerate() != TARGET_SAMPLE_RATE:
            raise ValueError(f"expected {TARGET_SAMPLE_RATE} Hz WAV: {path}")
        if wav.getnframes() <= 0:
            raise ValueError(f"empty WAV: {path}")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as src:
        for chunk in iter(lambda: src.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def materialize(input_path: Path, split: str, models: Path, output_dir: Path, manifest: Path) -> int:
    rows = _load_rows(input_path, split)
    pool = PiperPool(models)
    wav_dir = output_dir / "wav"
    tmp_dir = output_dir / "raw"
    manifest.parent.mkdir(parents=True, exist_ok=True)
    tmp_manifest = manifest.with_suffix(manifest.suffix + ".tmp")

    with tmp_manifest.open("w", encoding="utf-8") as out:
        for index, row in enumerate(rows, start=1):
            sample_id = row["id"]
            raw_wav = tmp_dir / f"{sample_id}.wav"
            final_wav = wav_dir / f"{sample_id}.wav"
            pool.synthesize(row["source_lang"], row["reference_source"], raw_wav)
            _resample_16k(raw_wav, final_wav)
            _validate_wav(final_wav)
            raw_wav.unlink(missing_ok=True)

            derived = dict(row)
            derived["source_kind"] = "synthetic-piper-safety"
            derived["wav"] = str(final_wav.resolve())
            derived["sha256"] = _sha256(final_wav)
            derived["tts_backend"] = BACKEND
            derived["tts_source_kind"] = row.get("source_kind")
            out.write(json.dumps(derived, ensure_ascii=False, sort_keys=True) + "\n")
            print(f"[{index}/{len(rows)}] {sample_id}", flush=True)

    tmp_manifest.replace(manifest)
    print(json.dumps({"manifest": str(manifest), "rows": len(rows), "split": split}, sort_keys=True))
    return len(rows)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, default=Path("testdata/mac-cpu/safety-corpus.jsonl"))
    parser.add_argument("--split", default="safety-holdout")
    parser.add_argument("--models", type=Path, default=Path("models"))
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    materialize(args.input, args.split, args.models, args.output_dir, args.manifest)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
