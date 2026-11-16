"""Export OPUS-MT to ONNX for every MT hop of the contest direction set, quantize to INT8, and
record the REAL tensor I/O names.

The Android runner introspects live ONNX sessions; io.json is diagnostic metadata. source.json
records the immutable upstream commit and export transformation for future reproducibility.

    python export_opus_mt.py                 # all six directions (MODEL_MANIFEST §1)
    python export_opus_mt.py --dir vi-en     # one direction
    python export_opus_mt.py --no-quant      # skip INT8 (keep fp32)

VI→ZH / VI→KO / KO→VI have no direct Helsinki graph (verified absent — HF answers 401); those pairs chain through
EN on-device (ChainedTranslator), so the hops exported here are exactly: vi-en, en-vi, en-zh,
zh-vi, en-ko, ko-en. en-ko uses the tc-big Marian architecture — same export path.

Needs: transformers, optimum[onnxruntime], onnx, onnxruntime, sentencepiece, sacremoses, torch.

STATUS: written against optimum's ORTModelForSeq2SeqLM export contract (encoder_model /
decoder_model / decoder_with_past_model). Tensor names are read back from the produced graphs, not
assumed — but VERIFY the printed io.json once after the first real export (manifest §1, ⚠️ rows).
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
from importlib.metadata import PackageNotFoundError, version
import json
from pathlib import Path
import re
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools/dev'))
from model_integrity import promote_tree

import paths

try:
    import onnx
    from optimum.onnxruntime import ORTModelForSeq2SeqLM
    from optimum.onnxruntime import ORTQuantizer
    from optimum.onnxruntime.configuration import AutoQuantizationConfig
    from transformers import AutoTokenizer
    from huggingface_hub import HfApi
except ImportError as e:
    sys.exit(f"missing dep ({e}); run: pip install -r ../requirements.txt  (+ torch CPU wheel)")

DIRECTIONS = {
    "vi-en": (paths.HF_OPUS_VI_EN, paths.MT_VI_EN),
    "en-vi": (paths.HF_OPUS_EN_VI, paths.MT_EN_VI),
    # Contest ZH/KO pairs (MODEL_MANIFEST §1): direct where a graph exists, pivot hops otherwise.
    "en-zh": (paths.HF_OPUS_EN_ZH, paths.MT_EN_ZH),
    "zh-vi": (paths.HF_OPUS_ZH_VI, paths.MT_ZH_VI),
    "en-ko": (paths.HF_OPUS_EN_KO, paths.MT_EN_KO),
    "ko-en": (paths.HF_OPUS_KO_EN, paths.MT_KO_EN),
}


def _io_names(onnx_path):
    """Read the real input/output tensor names from a produced ONNX graph."""
    m = onnx.load(str(onnx_path), load_external_data=False)
    ins = [i.name for i in m.graph.input]
    outs = [o.name for o in m.graph.output]
    return {"inputs": ins, "outputs": outs}


def export_one(direction: str, quantize: bool, revision: str | None = None) -> None:
    repo, out_dir = DIRECTIONS[direction]
    # Pin model and tokenizer before touching the previous export.
    if revision is not None and not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("--revision must be a full lowercase 40-hex commit")
    info = HfApi().model_info(repo, **({"revision": revision} if revision else {}))
    resolved = info.sha
    if not isinstance(resolved, str) or not re.fullmatch(r"[0-9a-f]{40}", resolved):
        raise ValueError("Hub did not return an immutable model commit")
    if revision is not None and resolved != revision:
        raise ValueError("Hub resolved a different commit than requested")
    out_dir.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.opus-export-', dir=out_dir.parent) as tmp:
        staged = Path(tmp) / 'model'
        staged.mkdir()
        _export_into(direction, quantize, repo, staged, resolved)
        for component in ('encoder_model', 'decoder_model', 'decoder_with_past_model'):
            graph = staged / f'{component}{"_quantized" if quantize else ""}.onnx'
            if not graph.is_file() or graph.stat().st_size == 0:
                raise RuntimeError(f'Missing exported graph: {graph}')
            onnx.checker.check_model(str(graph))
        promote_tree(staged, out_dir)


def _export_into(direction, quantize, repo, out_dir, resolved):
    print(f"[{direction}] exporting {repo}@{resolved} -> {out_dir}")

    # optimum exports encoder_model.onnx + decoder_model.onnx + decoder_with_past_model.onnx
    model = ORTModelForSeq2SeqLM.from_pretrained(repo, export=True, revision=resolved)
    model.save_pretrained(out_dir)
    del model
    AutoTokenizer.from_pretrained(repo, revision=resolved).save_pretrained(out_dir)

    if quantize:
        # INT8 dynamic quantization of each ONNX component (CPU/ARM64 friendly).
        for comp in ("encoder_model", "decoder_model", "decoder_with_past_model"):
            f = out_dir / f"{comp}.onnx"
            if not f.exists():
                continue
            q = ORTQuantizer.from_pretrained(out_dir, file_name=f"{comp}.onnx")
            qconfig = AutoQuantizationConfig.arm64(is_static=False, per_channel=False)
            q.quantize(save_dir=out_dir, quantization_config=qconfig)
        print(f"[{direction}] INT8 quantized (see *_quantized.onnx)")

    suffix = "_quantized.onnx" if quantize else ".onnx"
    selected_graphs = [stem + suffix for stem in ("encoder_model", "decoder_model", "decoder_with_past_model")]
    missing = [name for name in selected_graphs if not (out_dir / name).is_file() or (out_dir / name).stat().st_size == 0]
    if missing:
        raise ValueError(f"Incomplete selected export; no success provenance written: {missing}")

    # Record the REAL tensor names so the Kotlin runner reads, not guesses.
    io = {}
    for comp in ("encoder_model", "decoder_model", "decoder_with_past_model"):
        for suffix in ("_quantized", ""):
            f = out_dir / f"{comp}{suffix}.onnx"
            if f.exists():
                io[comp] = {"file": f.name, **_io_names(f)}
                break
    (out_dir / "io.json").write_text(json.dumps(io, indent=2, ensure_ascii=False))
    versions = {}
    for package in ("huggingface-hub", "transformers", "optimum", "onnx", "onnxruntime", "torch"):
        try:
            versions[package] = version(package)
        except PackageNotFoundError:
            versions[package] = None
    provenance = {
        "schema_version": 1,
        "model": repo,
        "revision": resolved,
        "exported_at_utc": datetime.now(timezone.utc).isoformat(),
        "precision": "float32+dynamic-int8" if quantize else "float32",
        "quantization": {"preset": "arm64", "is_static": False, "per_channel": False} if quantize else None,
        "selected_graphs": selected_graphs,
        "exporter_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "tool_versions": versions,
    }
    (out_dir / "source.json").write_text(json.dumps(provenance, indent=2) + "\n")
    print(f"[{direction}] wrote io.json:\n{json.dumps(io, indent=2, ensure_ascii=False)}")
    print(f"[{direction}] >>> VERIFY these names match OnnxTranslator's expectations once. <<<")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", choices=list(DIRECTIONS), help="one direction; default both")
    ap.add_argument("--no-quant", action="store_true")
    ap.add_argument("--revision", help="full immutable source commit; requires --dir")
    args = ap.parse_args()
    if args.revision and not args.dir:
        ap.error("--revision requires --dir so one repo commit is not reused across directions")
    targets = [args.dir] if args.dir else list(DIRECTIONS)
    for d in targets:
        export_one(d, quantize=not args.no_quant, revision=args.revision)
    print("[export_opus_mt] done.")


if __name__ == "__main__":
    main()
