"""Shared target paths — the Python side of the MODEL_MANIFEST contract.

Every script writes to these exact locations so the Android runners (which read the same
relative paths under models/) find what they expect. Change a path in MODEL_MANIFEST.md, then
change it here.
"""
from __future__ import annotations
import pathlib

# Repo root = two levels up from this file (model-tools-python/scripts/paths.py).
REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
MODELS = REPO_ROOT / "models"

VAD = MODELS / "vad"
# Contest direction set (OneVoice AI Challenge: VI↔EN, VI↔Mandarin, VI↔Korean). Direct graphs
# where they exist, pivot hops through English where they do not (MODEL_MANIFEST §1):
#   VI→EN, EN→VI, ZH→VI, VI→EN+EN→ZH, VI→EN+EN→KO, KO→EN+EN→VI.
MT_VI_EN = MODELS / "translation" / "opus-mt-vi-en"
MT_EN_VI = MODELS / "translation" / "opus-mt-en-vi"
MT_EN_ZH = MODELS / "translation" / "opus-mt-en-zh"
MT_ZH_VI = MODELS / "translation" / "opus-mt-zh-vi"
MT_EN_KO = MODELS / "translation" / "opus-mt-en-ko"
MT_KO_EN = MODELS / "translation" / "opus-mt-ko-en"
ASR_ONNX = MODELS / "speech_to_text" / "whisper-base-onnx"
TTS_PIPER = MODELS / "text_to_speech" / "piper"
TTS_CACHE = MODELS / "text_to_speech" / "cache"

# HF source repos (MODEL_MANIFEST §1–4). VI→ZH / VI→KO / KO→VI have NO direct Helsinki graph
# (verified absent — HF answers 401 "Repository not found" for non-existent repos, its
# anti-enumeration response); those pairs chain through EN on-device via ChainedTranslator, so the
# hops exported here are exactly the six above.
HF_OPUS_VI_EN = "Helsinki-NLP/opus-mt-vi-en"
HF_OPUS_EN_VI = "Helsinki-NLP/opus-mt-en-vi"
HF_OPUS_EN_ZH = "Helsinki-NLP/opus-mt-en-zh"
HF_OPUS_ZH_VI = "Helsinki-NLP/opus-mt-zh-vi"          # direct ZH→VI graph exists
HF_OPUS_EN_KO = "Helsinki-NLP/opus-mt-tc-big-en-ko"   # tc-big: the only published EN→KO graph
HF_OPUS_KO_EN = "Helsinki-NLP/opus-mt-ko-en"
HF_SILERO = "snakers4/silero-vad"
# The HF silero-vad mirror is gated (401 anonymous); GitHub raw of the release tag is canonical.
# Pinned tag — a moving ref like `master` could drift under a safety-app artifact.
SILERO_GITHUB_RAW = "https://github.com/snakers4/silero-vad/raw/v5.1.2/src/silero_vad/data/silero_vad.onnx"

# Piper voices: sherpa-onnx ships READY bundles (model + tokens.txt + espeak-ng-data) — NOT the bare
# .onnx from rhasspy/piper-voices (which lacks tokens/espeak that sherpa's OfflineTts needs). Each
# bundle extracts to a dir `vits-piper-<voice>/` (or `vits-mimic3-<voice>/` for KO) containing
# `<voice>.onnx`. All four are plain Piper bundles — one engine path on-device for every language.
# Names verified live against the sherpa-onnx tts-models release listing.
SHERPA_TTS_RELEASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
# The previous 25hours_single-low model has only 130 symbol embeddings while eSpeak vi emits
# additional Vietnamese phoneme symbols (including tone digits and U+032A). sherpa therefore
# skipped phonemes at runtime. vais1000-medium ships the matching extended phoneme map and is
# licensed CC BY 4.0 by its model card.
PIPER_VI_BUNDLE = "vits-piper-vi_VN-vais1000-medium"
PIPER_EN_BUNDLE = "vits-piper-en_US-lessac-medium"
PIPER_ZH_BUNDLE = "vits-piper-zh_CN-huayan-medium"
PIPER_KO_BUNDLE = "vits-mimic3-ko_KO-kss_low"

# GitHub release asset digest reported by the sherpa-onnx tts-models release metadata.
PIPER_ARCHIVE_SHA256 = {
    PIPER_VI_BUNDLE: "fa1367710767d36ed5cf13b4a449e20c35ffd12791c2e47c2e64142bfa55551a",
}

def ensure(*dirs: pathlib.Path) -> None:
    for d in dirs:
        d.mkdir(parents=True, exist_ok=True)
