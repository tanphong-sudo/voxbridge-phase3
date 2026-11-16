#!/usr/bin/env python3
"""Small Piper listening comparison; never changes the production voice or safety cache."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import time

import tts_bridge

# Already verbalized text, identical for both configurations. No reference feeds ASR/MT.
PHRASES = {
    "safety": "Khẩn cấp, dừng máy ép số bốn vì rò rỉ khí. Không vào khu vực nguy hiểm số bảy.",
    "measurement": "Kiểm tra khoảng cách mười lăm mi li mét và điện áp hai trăm hai mươi vôn.",
}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models", type=Path, default=Path("models"))
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()
    out = args.out_dir.resolve()
    # Refuse overwriting an earlier audition's evidence.
    out.mkdir(parents=True, exist_ok=False)
    root, onnx, tokens, data = tts_bridge.voice_bundle(args.models.resolve(), "vi")
    voice_config = json.loads(onnx.with_suffix(".onnx.json").read_text())["inference"]
    sherpa = tts_bridge._load_sherpa()
    rows = []
    for name, noise, duration_noise in [
        ("current", 0.0, 0.0),
        ("voice-default", float(voice_config["noise_scale"]), float(voice_config["noise_w"])),
    ]:
        started = time.perf_counter()
        engine = sherpa.OfflineTts(sherpa.OfflineTtsConfig(model=sherpa.OfflineTtsModelConfig(
            vits=sherpa.OfflineTtsVitsModelConfig(model=str(onnx), tokens=str(tokens),
                data_dir=str(data), noise_scale=noise, noise_scale_w=duration_noise,
                length_scale=1.0), num_threads=2, provider="cpu")))
        load_ms = (time.perf_counter() - started) * 1000
        engine.generate("Xin chào.", sid=0, speed=1.0)
        for phrase_id, text in PHRASES.items():
            started = time.perf_counter()
            audio = engine.generate(text, sid=0, speed=1.0)
            elapsed_ms = (time.perf_counter() - started) * 1000
            import array
            import math
            import sys
            samples = list(audio.samples)
            if not samples or not all(math.isfinite(x) for x in samples):
                raise RuntimeError("Empty or non-finite generated audio")
            pcm = array.array("h", (int(max(-1.0, min(1.0, x)) * 32767) for x in samples))
            if sys.byteorder != "little":
                pcm.byteswap()
            wav = out / f"{name}-{phrase_id}.wav"
            tts_bridge._write_pcm16_wav(wav, pcm.tobytes(), int(audio.sample_rate))
            rows.append(dict(config=name, phrase=phrase_id, text=text,
                noise_scale=noise, noise_scale_w=duration_noise,
                load_ms=load_ms, synthesis_ms=elapsed_ms,
                rtf=elapsed_ms / 1000 / (len(samples) / audio.sample_rate),
                **vars(tts_bridge._stats(samples, int(audio.sample_rate), wav)),
                sha256=hashlib.sha256(wav.read_bytes()).hexdigest()))
        del engine
    summary = dict(scope="Mac CPU Python sherpa audition, not Android or human quality acceptance",
        voice=root.name, model_sha256=hashlib.sha256(onnx.read_bytes()).hexdigest(),
        sherpa_version=getattr(sherpa, "__version__", "unknown"), samples=rows,
        human_ratings=None, production_changed=False)
    (out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
