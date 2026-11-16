#!/usr/bin/env python3
"""Mac CPU Piper bridge for the isolated Task-4 TTS slice.

The project does not currently contain a macOS sherpa-onnx JNI dylib; the Android AAR only ships
Android/Linux shared objects. Therefore this adapter is explicitly the permitted fallback backend:

    tts_backend=python-bridge

It uses the exact VI/EN sherpa Piper bundles and deterministic VITS settings used by PiperTts.kt.
The caller supplies the already-normalized spoken text (SpokenTextNormalizer is shared Kotlin and
remains the single source of truth for verbalizing measurements).
"""

from __future__ import annotations

import argparse
import array
from dataclasses import asdict, dataclass
import json
import math
from pathlib import Path
import sys
import wave


BACKEND = "python-bridge"
VOICE_DIR = {
    "vi": "vits-piper-vi_VN-vais1000-medium",
    "en": "vits-piper-en_US-lessac-medium",
}
VI_REQUIRED_PHONEME_TOKENS = frozenset("0123456789") | {"\u032a"}


class TtsRuntimeUnavailable(RuntimeError):
    pass


class TtsArtifactError(RuntimeError):
    pass


class CacheValidationError(RuntimeError):
    pass


@dataclass(frozen=True)
class AudioStats:
    backend: str
    sample_rate: int
    sample_count: int
    duration_seconds: float
    rms: float
    peak: float
    clipping_samples: int
    wav: str


@dataclass(frozen=True)
class CachedPcm:
    pcm16le: bytes
    sample_rate: int

    @property
    def sample_count(self) -> int:
        return len(self.pcm16le) // 2


def voice_bundle(models: Path, lang: str) -> tuple[Path, Path, Path, Path]:
    try:
        name = VOICE_DIR[lang]
    except KeyError as exc:
        raise TtsArtifactError(f"unsupported Task-4 TTS language: {lang}") from exc
    root = models / "text_to_speech" / "piper" / name
    onnx = sorted(root.glob("*.onnx"))
    if len(onnx) != 1:
        raise TtsArtifactError(f"expected exactly one Piper ONNX model under {root}, found {len(onnx)}")
    tokens = root / "tokens.txt"
    data_dir = root / "espeak-ng-data"
    if not tokens.is_file() or tokens.stat().st_size == 0:
        raise TtsArtifactError(f"missing/non-empty tokens.txt: {tokens}")
    if not data_dir.is_dir():
        raise TtsArtifactError(f"missing espeak-ng-data directory: {data_dir}")
    if lang == "vi":
        token_set = {
            line.rsplit(" ", 1)[0]
            for line in tokens.read_text(encoding="utf-8").splitlines()
            if " " in line
        }
        missing = sorted(VI_REQUIRED_PHONEME_TOKENS - token_set)
        if missing:
            printable = ", ".join(f"U+{ord(token):04X}" for token in missing)
            raise TtsArtifactError(
                "Vietnamese Piper token inventory is incompatible with the eSpeak vi phoneme "
                f"stream; missing {printable}: {tokens}"
            )
    return root, onnx[0], tokens, data_dir


def read_cached_pcm(models: Path, phrase_id: str) -> CachedPcm:
    cache = models / "text_to_speech" / "cache"
    pcm = cache / f"{phrase_id}.pcm"
    rate_file = cache / f"{phrase_id}.sr"
    if not pcm.is_file():
        raise CacheValidationError(f"cached PCM missing: {pcm}")
    if not rate_file.is_file():
        raise CacheValidationError(f"cached sample-rate sidecar missing: {rate_file}")
    data = pcm.read_bytes()
    if not data:
        raise CacheValidationError(f"cached PCM is empty: {pcm}")
    if len(data) % 2:
        raise CacheValidationError(f"cached PCM byte length is not PCM16-aligned: {pcm} ({len(data)} bytes)")
    raw_rate = rate_file.read_text(encoding="utf-8").strip()
    try:
        rate = int(raw_rate)
    except ValueError as exc:
        raise CacheValidationError(f"cached sample rate is not an integer: {rate_file}={raw_rate!r}") from exc
    if rate < 8_000 or rate > 192_000:
        raise CacheValidationError(f"cached sample rate out of range: {rate_file}={rate}")
    return CachedPcm(data, rate)


def _write_pcm16_wav(destination: Path, pcm16le: bytes, sample_rate: int) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(destination), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(sample_rate)
        out.writeframes(pcm16le)


def cached_phrase_to_wav(models: Path, phrase_id: str, destination: Path) -> AudioStats:
    cached = read_cached_pcm(models, phrase_id)
    _write_pcm16_wav(destination, cached.pcm16le, cached.sample_rate)
    samples = array.array("h")
    samples.frombytes(cached.pcm16le)
    if sys.byteorder != "little":
        samples.byteswap()
    floats = [sample / 32768.0 for sample in samples]
    return _stats(floats, cached.sample_rate, destination)


def _load_sherpa():
    try:
        import sherpa_onnx  # type: ignore
    except ImportError as exc:
        raise TtsRuntimeUnavailable(
            "tts_backend=python-bridge unavailable: Python package 'sherpa_onnx' is not installed"
        ) from exc
    return sherpa_onnx


def synthesize(models: Path, lang: str, spoken_text: str, destination: Path) -> AudioStats:
    if not spoken_text.strip():
        raise ValueError("spoken_text must not be blank")
    _root, onnx, tokens, data_dir = voice_bundle(models, lang)
    sherpa_onnx = _load_sherpa()

    # Match Android PiperTts exactly: CPU, 2 threads, deterministic VITS noise settings, sid 0,
    # speed 1.0. These kwargs are part of sherpa-onnx's Piper/VITS Python configuration.
    vits = sherpa_onnx.OfflineTtsVitsModelConfig(
        model=str(onnx),
        tokens=str(tokens),
        data_dir=str(data_dir),
        noise_scale=0.0,
        noise_scale_w=0.0,
        length_scale=1.0,
    )
    config = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=vits,
            num_threads=2,
            provider="cpu",
        )
    )
    tts = sherpa_onnx.OfflineTts(config)
    audio = tts.generate(spoken_text, sid=0, speed=1.0)
    sample_rate = int(audio.sample_rate)
    samples = [float(value) for value in audio.samples]
    if sample_rate <= 0:
        raise RuntimeError(f"Piper returned invalid sample rate: {sample_rate}")
    if not samples:
        raise RuntimeError("Piper returned zero samples")
    if any(not math.isfinite(value) for value in samples):
        raise RuntimeError("Piper returned NaN/Inf samples")

    pcm = array.array(
        "h",
        (max(-32768, min(32767, int(max(-1.0, min(1.0, value)) * 32767.0))) for value in samples),
    )
    if sys.byteorder != "little":
        pcm.byteswap()
    _write_pcm16_wav(destination, pcm.tobytes(), sample_rate)
    return _stats(samples, sample_rate, destination)


def _stats(samples: list[float], sample_rate: int, destination: Path) -> AudioStats:
    if not samples:
        raise ValueError("cannot score empty audio")
    peak = max(abs(value) for value in samples)
    rms = math.sqrt(sum(value * value for value in samples) / len(samples))
    clipping = sum(1 for value in samples if abs(value) >= 0.99)
    return AudioStats(
        backend=BACKEND,
        sample_rate=sample_rate,
        sample_count=len(samples),
        duration_seconds=len(samples) / sample_rate,
        rms=rms,
        peak=peak,
        clipping_samples=clipping,
        wav=str(destination),
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--models", type=Path, default=Path("models"))
    parser.add_argument("--lang", choices=sorted(VOICE_DIR))
    parser.add_argument("--text", help="already-normalized spoken text from SpokenTextNormalizer")
    parser.add_argument("--cache-id")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    if bool(args.cache_id) == bool(args.text):
        parser.error("choose exactly one of --text or --cache-id")
    try:
        if args.cache_id:
            stats = cached_phrase_to_wav(args.models, args.cache_id, args.out)
        else:
            if not args.lang:
                parser.error("--lang is required with --text")
            stats = synthesize(args.models, args.lang, args.text, args.out)
    except (TtsRuntimeUnavailable, TtsArtifactError, CacheValidationError, ValueError, RuntimeError) as exc:
        print(f"tts_backend={BACKEND}", file=sys.stderr)
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2

    print(f"tts_backend={BACKEND}")
    print(json.dumps(asdict(stats), sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
