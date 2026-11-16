"""Export OpenAI Whisper Base to ONNX + shared assets used by the Android runtime.

Writes to models/speech_to_text/whisper-base-onnx/:
  encoder_model.onnx / decoder_model.onnx / decoder_with_past_model.onnx   (optimum export)
  tokenizer + preprocessor files
  mel_80_201.f32.bin      — Whisper's 80x201 log-mel filterbank, flat little-endian f32.
                            Host-side mel preprocessing is required by the Android runtime.
  whisper_tokens.json     — the REAL special-token ids read from the HF WhisperTokenizer
                            (SOT/EOT/language vi/en/transcribe/notimestamps/nospeech). The
                            Android decode loop reads ids from here — never hard-coded.

    python export_whisper_onnx.py

Needs: transformers, optimum[onnxruntime], torch (CPU wheel ok), numpy.
STATUS: optimum's ORTModelForSpeechSeq2Seq emits the encoder_model/decoder_model/
decoder_with_past_model filenames the Android loaders expect (ModelPaths.cpuSetReady reads them).
"""
from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools/dev'))
from model_integrity import promote_tree

import numpy as np

import paths

try:
    from transformers import AutoProcessor, AutoTokenizer
except ImportError as e:
    sys.exit(f"missing dep ({e}); run: pip install -r ../requirements.txt  (+ torch CPU wheel)")
# NOTE: optimum is imported lazily inside main() — shared token/mel assets need only transformers + numpy.

MODEL = "openai/whisper-base"

# Special tokens whose REAL ids the decode loop needs. Each entry is a candidate alias list —
# resolution does a DIRECT vocab lookup and verifies the round-trip (NEVER
# tok.convert_tokens_to_ids(), which silently returns the unk id 50257 for absent names).
# Resolve every id from the selected model tokenizer at export time; no token id is hard-coded.
NAMED_TOKENS = {
    "sot": ["<|startoftranscript|>"],
    "eot": ["<|endoftext|>"],
    "transcribe": ["<|transcribe|>"],
    "notimestamps": ["<|notimestamps|>"],
    "nospeech": ["<|nocaptions|>", "<|nospeech|>"],
}
# Languages the app can prompt (core Language enum: VI, EN, ZH, KO) — all are Whisper's ISO
# language tokens in the 50258..51864 window; ids verified against the real tokenizer at export.
LANG_TOKENS = {"vi": "<|vi|>", "en": "<|en|>", "zh": "<|zh|>", "ko": "<|ko|>"}


def export_tokens(model: str = MODEL, destination=None, revision=None) -> dict:
    """Resolve the real special-token ids from the HF tokenizer and write whisper_tokens.json."""
    tok = AutoTokenizer.from_pretrained(model, revision=revision)
    vocab = tok.get_vocab()

    def token_id(candidates: list[str], role: str) -> int:
        for name in candidates:
            tid = vocab.get(name)
            if tid is not None and tok.convert_ids_to_tokens(int(tid)) == name:
                return int(tid)
        sys.exit(f"[whisper] no vocab id resolves for role={role} (tried {candidates}) — "
                 f"unexpected tokenizer layout; inspect the vocab before trusting the export.")

    ids = {role: token_id(cands, role) for role, cands in NAMED_TOKENS.items()}
    langs = {code: token_id([token], f"language {code}") for code, token in LANG_TOKENS.items()}
    out = {
        "model": model,
        "vocab_size": int(max(vocab.values())) + 1,
        **ids,
        "languages": langs,
        # Forced-prompt order right after SOT (AI Hub decode loop): language -> transcribe ->
        # notimestamps, fed step-by-step after the SOT step.
        "prompt_after_sot": ["languages.<code>", "transcribe", "notimestamps"],
        "usage_note": "read ids from this file; do not hard-code token ids in Kotlin",
    }
    dest = (destination or paths.ASR_ONNX) / "whisper_tokens.json"
    dest.write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n")
    print(f"[whisper] wrote {dest}")
    print(f"[whisper]   sot={ids['sot']} eot={ids['eot']} transcribe={ids['transcribe']} "
          f"notimestamps={ids['notimestamps']} nospeech={ids['nospeech']} "
          f"languages={langs} vocab={out['vocab_size']}")
    return out


def export_mel(model: str = MODEL, destination=None, revision=None) -> None:
    """Persist Whisper's exact 80x201 mel filterbank as flat little-endian f32 [80][201].

    The host-side mel must equal what the encoder was trained on or accuracy degrades, so we
    pull the filterbank from Whisper-derived sources (in preference order) and FAIL HARD if none
    is available — a missing/guessed filterbank silently degrades ASR, which we never accept.
    """
    mel = _mel_filterbank(model, revision)
    if mel is None:
        sys.exit(
            "[whisper] could not obtain Whisper's mel filterbank from any known source.\n"
            "Fix: pip install openai-whisper  (or upgrade transformers to >= 4.34)."
        )
    assert mel.shape == (80, 201), f"unexpected mel shape {mel.shape}"
    dest = (destination or paths.ASR_ONNX) / "mel_80_201.f32.bin"
    dest.write_bytes(np.ascontiguousarray(mel, dtype=np.float32).tobytes(order="C"))
    print(f"[whisper] wrote {dest.name}  shape={mel.shape}  ({mel.size * 4} bytes)")


def _mel_filterbank(model: str = MODEL, revision=None) -> np.ndarray | None:
    """Try every known source for Whisper's 80x201 filterbank; return None if all fail.

    VERIFIED (transformers 5.9, live): transformers.audio_utils.mel_filter_bank with
    norm="slaney", mel_scale="slaney" matches fe.mel_filters exactly (max-abs-diff 0.0); the htk
    variant is WRONG (diff ~0.036) — Whisper uses slaney mel scale + slaney norm.
    """
    # (1) openai-whisper's own matrix — the exact filters the model was trained with.
    try:
        import whisper  # type: ignore
        return whisper.audio.mel_filters(device="cpu", n_mels=80).numpy().astype(np.float32)
    except Exception:
        pass
    # (2) The instance attribute WhisperFeatureExtractor itself computes for preprocessing
    #     (shape [201, 80] = [freq_bins, mel_bins] -> transpose). Same filters, no extra dep.
    try:
        from transformers import WhisperFeatureExtractor
        fe = WhisperFeatureExtractor.from_pretrained(model, revision=revision)
        arr = np.asarray(getattr(fe, "mel_filters"), dtype=np.float32)
        if arr.shape == (201, 80):
            return arr.T.copy()
    except Exception:
        pass
    # (3) transformers' public audio util with the VERIFIED slaney/slaney parameters.
    try:
        from transformers.audio_utils import mel_filter_bank  # type: ignore
        m = mel_filter_bank(num_frequency_bins=201, num_mel_filters=80, min_frequency=0.0,
                            max_frequency=8000.0, sampling_rate=16000, norm="slaney",
                            mel_scale="slaney")
        return np.asarray(m, dtype=np.float32).T  # [201, 80] -> [80, 201]
    except Exception:
        pass
    # (4) librosa's slaney-normalised mel — equivalent formulation of the same filters.
    try:
        import librosa
        return librosa.filters.mel(sr=16000, n_fft=400, n_mels=80, norm="slaney", htk=False).astype(np.float32)
    except Exception:
        pass
    return None


def main() -> None:
    try:
        from optimum.onnxruntime import ORTModelForSpeechSeq2Seq
    except ImportError as e:
        sys.exit(f"missing dep ({e}); run: pip install -r ../requirements.txt  (+ torch CPU wheel)")
    print(f"[whisper] exporting {MODEL} -> {paths.ASR_ONNX}")
    paths.ASR_ONNX.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.whisper-export-', dir=paths.ASR_ONNX.parent) as tmp:
        staged = Path(tmp)/'model'
        m = ORTModelForSpeechSeq2Seq.from_pretrained(MODEL, export=True)
        m.save_pretrained(staged)
        del m
        AutoProcessor.from_pretrained(MODEL).save_pretrained(staged)
        export_tokens(destination=staged)
        export_mel(destination=staged)
        import onnx
        for component in ('encoder_model', 'decoder_model', 'decoder_with_past_model'):
            onnx.checker.check_model(str(staged/f'{component}.onnx'))
        promote_tree(staged, paths.ASR_ONNX)
    print("[whisper] done. Whisper Base CPU ONNX + shared assets ready.")


if __name__ == "__main__":
    main()
