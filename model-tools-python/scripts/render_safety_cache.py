"""Render the safety-phrase audio cache to PCM.

The high-frequency safety phrases (ask-repeat prompt, stop/evacuate, do-not-enter, etc.) are
pre-rendered once so the app can play them without live synthesis. Device first-audio latency
must be measured separately. Dynamic sentences still go through Piper at runtime.

    python render_safety_cache.py

Needs: the Piper voice bundles fetched (fetch_models.py --piper) and `pip install sherpa-onnx`.

Rendering uses the same sherpa-onnx voice engine and sample rate as the app.
Thread count/runtime differences can produce small floating-point/PCM rounding differences. A sidecar <id>.sr
records the voice-native rate (read by ModelPaths.cachedPhraseRate). index.txt is a
human/tooling manifest of the grid — the Android side plays a phrase by CONVENTION
(`ask_repeat_<lang>` / `no_speech_<lang>` built in PushToTalkViewModel) and never reads
index.txt; VI/EN safety commands can also be selected by CachedPhraseIndex after output-policy approval.
"""
from __future__ import annotations
import argparse
import sys

import paths

# id -> (language, text). Output: cache/<id>.pcm (mono s16le) + cache/<id>.sr (sample rate).
# Rate is the Piper VOICE-NATIVE rate (medium voices are typically 22050 Hz, NOT 16 kHz) — read
# from the voice's .onnx.json so playback never assumes the wrong rate.
#
# FULL 4×6 GRID over the contest languages (VI, EN, ZH, KO): doc 14 §6 grades ASK_REPEAT /
# NO_SPEECH by prompting the SPEAKER, so the prompt plays in the SOURCE language — and every one
# of the four languages can be a source across the VI↔EN / VI↔ZH / VI↔KO pairs. The old vi/en
# set had no `no_speech_en` at all (an EN-source no-speech event would have played silence).
PHRASES = {
    "ask_repeat_vi": ("vi", "Xin vui lòng nói lại."),
    "ask_repeat_en": ("en", "Please repeat."),
    "ask_repeat_zh": ("zh", "请再说一遍。"),
    "ask_repeat_ko": ("ko", "다시 말씀해 주세요."),
    "no_speech_vi": ("vi", "Không nghe rõ."),
    "no_speech_en": ("en", "I could not hear you clearly."),
    "no_speech_zh": ("zh", "没有听清楚。"),
    "no_speech_ko": ("ko", "잘 들리지 않았습니다."),
    "stop_conveyor_vi": ("vi", "Dừng băng chuyền."),
    "stop_conveyor_en": ("en", "Stop the conveyor."),
    "stop_conveyor_zh": ("zh", "停止传送带。"),
    "stop_conveyor_ko": ("ko", "컨베이어를 정지하세요."),
    "stop_machine_vi": ("vi", "Dừng máy."),
    "stop_machine_en": ("en", "Stop the machine."),
    "stop_machine_zh": ("zh", "停止机器。"),
    "stop_machine_ko": ("ko", "기계를 정지하세요."),
    "do_not_enter_vi": ("vi", "Không vào khu vực nguy hiểm."),
    "do_not_enter_en": ("en", "Do not enter the hazard zone."),
    "do_not_enter_zh": ("zh", "不要进入危险区域。"),
    "do_not_enter_ko": ("ko", "위험 구역에 들어가지 마십시오."),
    "evacuate_vi": ("vi", "Sơ tán ngay lập tức."),
    "evacuate_en": ("en", "Evacuate immediately."),
    "evacuate_zh": ("zh", "立即疏散。"),
    "evacuate_ko": ("ko", "즉시 대피하십시오."),
}

BUNDLE = {
    "vi": paths.PIPER_VI_BUNDLE,
    "en": paths.PIPER_EN_BUNDLE,
    "zh": paths.PIPER_ZH_BUNDLE,
    "ko": paths.PIPER_KO_BUNDLE,
}

PHASE2_PHRASE_IDS = (
    "ask_repeat_vi",
    "ask_repeat_en",
    "no_speech_vi",
    "no_speech_en",
)

# One sherpa-onnx OfflineTts per language, built from the extracted bundle dir. Rendering with the
# same engine/voice family as the app preserves the format/rate contract. PCM bit identity also
# depends on thread count and runtime; retain exact renderer settings in provenance.
_engines: dict[str, object] = {}


def _engine(lang: str):
    if lang in _engines:
        return _engines[lang]
    try:
        import sherpa_onnx
    except ImportError:
        sys.exit("[cache] sherpa-onnx not installed: pip install sherpa-onnx "
                 "(same engine the app uses, so the cache matches live audio).")
    voice_dir = paths.TTS_PIPER / BUNDLE[lang]
    onnx = next(iter(voice_dir.glob("*.onnx")), None)
    if onnx is None:
        sys.exit(f"[cache] voice bundle missing: {voice_dir} — run fetch_models.py --piper first")
    cfg = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=str(onnx),
                tokens=str(voice_dir / "tokens.txt"),
                data_dir=str(voice_dir / "espeak-ng-data"),
                noise_scale=0.0,
                noise_scale_w=0.0,
                length_scale=1.0,
            ),
            num_threads=2,
            provider="cpu",
        ),
    )
    _engines[lang] = sherpa_onnx.OfflineTts(cfg)
    return _engines[lang]


def render(pid: str, lang: str, text: str) -> None:
    import array
    tts = _engine(lang)
    audio = tts.generate(text, sid=0, speed=1.0)  # .samples (float [-1,1]), .sample_rate
    pcm16 = array.array("h", (max(-32768, min(32767, int(s * 32767))) for s in audio.samples))
    (paths.TTS_CACHE / f"{pid}.pcm").write_bytes(pcm16.tobytes())
    # Sidecar with the voice-native sample rate (read by ModelPaths.cachedPhraseRate).
    (paths.TTS_CACHE / f"{pid}.sr").write_text(str(audio.sample_rate))
    print(f"[cache] {pid} ({lang}) -> {pid}.pcm  ({len(pcm16) * 2} bytes @ {audio.sample_rate} Hz)")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--phase2",
        action="store_true",
        help="render only the four VI/EN prompts staged by the final Phase-2 product profile",
    )
    parser.add_argument("--commands-only", action="store_true",
                        help="render the eight VI/EN safety commands; preserve existing prompts")
    args = parser.parse_args()
    selected = (
        {pid: value for pid, value in PHRASES.items()
         if pid.startswith(("stop_conveyor_", "stop_machine_", "do_not_enter_", "evacuate_"))
         and value[0] in ("vi", "en")}
        if args.commands_only else
        {pid: PHRASES[pid] for pid in PHASE2_PHRASE_IDS}
        if args.phase2
        else PHRASES
    )
    paths.ensure(paths.TTS_CACHE)
    for pid, (lang, text) in selected.items():
        render(pid, lang, text)
    # Manifest of phrase ids the Android cache loader keys on.
    ids = [pid for pid in PHRASES if (paths.TTS_CACHE / f"{pid}.pcm").is_file()
           and (paths.TTS_CACHE / f"{pid}.sr").is_file()]
    (paths.TTS_CACHE / "index.txt").write_text("\n".join(ids) + "\n")
    print(f"[cache] wrote {len(selected)} phrases + index.txt")


if __name__ == "__main__":
    main()
