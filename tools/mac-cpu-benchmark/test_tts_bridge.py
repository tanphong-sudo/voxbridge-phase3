from __future__ import annotations

import array
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
import wave

sys.path.insert(0, str(Path(__file__).resolve().parent))

import tts_bridge


def _voice_tree(models: Path, lang: str = "en") -> Path:
    name = tts_bridge.VOICE_DIR[lang]
    root = models / "text_to_speech" / "piper" / name
    root.mkdir(parents=True)
    (root / "voice.onnx").write_bytes(b"onnx")
    (root / "tokens.txt").write_text("a 1\n", encoding="utf-8")
    (root / "espeak-ng-data").mkdir()
    return root


def _cache_root(models: Path) -> Path:
    root = models / "text_to_speech" / "cache"
    root.mkdir(parents=True)
    return root


class TtsBridgeTest(unittest.TestCase):
    def test_vi_bundle_rejects_old_incomplete_phoneme_inventory(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            models = Path(tmp)
            root = _voice_tree(models, "vi")
            with self.assertRaisesRegex(tts_bridge.TtsArtifactError, "eSpeak vi phoneme stream"):
                tts_bridge.voice_bundle(models, "vi")

            required = sorted(tts_bridge.VI_REQUIRED_PHONEME_TOKENS, key=ord)
            (root / "tokens.txt").write_text(
                "a 1\n" + "".join(f"{token} {index + 2}\n" for index, token in enumerate(required)),
                encoding="utf-8",
            )
            self.assertEqual(tts_bridge.voice_bundle(models, "vi")[0], root)

    def test_missing_cache_pcm_is_explicit(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            models = Path(tmp)
            _cache_root(models)
            with self.assertRaisesRegex(tts_bridge.CacheValidationError, "cached PCM missing"):
                tts_bridge.read_cached_pcm(models, "ask_repeat_en")

    def test_missing_and_corrupt_rate_are_explicit(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            models = Path(tmp)
            cache = _cache_root(models)
            (cache / "x.pcm").write_bytes(b"\x00\x00")
            with self.assertRaisesRegex(tts_bridge.CacheValidationError, "sidecar missing"):
                tts_bridge.read_cached_pcm(models, "x")
            (cache / "x.sr").write_text("not-a-rate", encoding="utf-8")
            with self.assertRaisesRegex(tts_bridge.CacheValidationError, "not an integer"):
                tts_bridge.read_cached_pcm(models, "x")
            (cache / "x.sr").write_text("0", encoding="utf-8")
            with self.assertRaisesRegex(tts_bridge.CacheValidationError, "out of range"):
                tts_bridge.read_cached_pcm(models, "x")

    def test_corrupt_pcm_alignment_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            models = Path(tmp)
            cache = _cache_root(models)
            (cache / "x.pcm").write_bytes(b"abc")
            (cache / "x.sr").write_text("22050", encoding="utf-8")
            with self.assertRaisesRegex(tts_bridge.CacheValidationError, "PCM16-aligned"):
                tts_bridge.read_cached_pcm(models, "x")

    def test_valid_cached_pcm_keeps_native_rate_in_wav(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            models = root / "models"
            cache = _cache_root(models)
            pcm = array.array("h", [0, 1000, -1000, 32767, -32768])
            if sys.byteorder != "little":
                pcm.byteswap()
            (cache / "x.pcm").write_bytes(pcm.tobytes())
            (cache / "x.sr").write_text("22050", encoding="utf-8")
            out = root / "x.wav"
            stats = tts_bridge.cached_phrase_to_wav(models, "x", out)
            self.assertEqual(stats.backend, "python-bridge")
            self.assertEqual(stats.sample_rate, 22050)
            self.assertEqual(stats.sample_count, 5)
            with wave.open(str(out), "rb") as wav:
                self.assertEqual(wav.getframerate(), 22050)
                self.assertEqual(wav.getnchannels(), 1)
                self.assertEqual(wav.getsampwidth(), 2)

    def test_python_bridge_uses_exact_cpu_deterministic_vits_settings(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            models = root / "models"
            _voice_tree(models, "en")
            captured: dict[str, object] = {}

            class Vits:
                def __init__(self, **kwargs):
                    captured["vits"] = kwargs

            class Model:
                def __init__(self, **kwargs):
                    captured["model"] = kwargs

            class Config:
                def __init__(self, **kwargs):
                    captured["config"] = kwargs

            class Audio:
                sample_rate = 22050
                samples = [0.0, 0.1, -0.1, 0.2]

            class Engine:
                def __init__(self, config):
                    captured["engine_config"] = config

                def generate(self, text, sid, speed):
                    captured["generate"] = (text, sid, speed)
                    return Audio()

            fake = types.SimpleNamespace(
                OfflineTtsVitsModelConfig=Vits,
                OfflineTtsModelConfig=Model,
                OfflineTtsConfig=Config,
                OfflineTts=Engine,
            )
            old = sys.modules.get("sherpa_onnx")
            sys.modules["sherpa_onnx"] = fake
            try:
                out = root / "synth.wav"
                stats = tts_bridge.synthesize(models, "en", "two hundred twenty volts", out)
            finally:
                if old is None:
                    del sys.modules["sherpa_onnx"]
                else:
                    sys.modules["sherpa_onnx"] = old

            vits = captured["vits"]
            self.assertEqual(vits["noise_scale"], 0.0)
            self.assertEqual(vits["noise_scale_w"], 0.0)
            self.assertEqual(vits["length_scale"], 1.0)
            model = captured["model"]
            self.assertEqual(model["num_threads"], 2)
            self.assertEqual(model["provider"], "cpu")
            self.assertEqual(captured["generate"], ("two hundred twenty volts", 0, 1.0))
            self.assertEqual(stats.sample_rate, 22050)
            self.assertTrue(out.is_file())


if __name__ == "__main__":
    unittest.main()
