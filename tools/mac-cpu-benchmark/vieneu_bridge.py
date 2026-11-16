#!/usr/bin/env python3
"""Selected Mac TTS adapter: Vietnamese Nano, English Piper; no Android/QNN claim."""
from __future__ import annotations

import array
from dataclasses import asdict, replace
import hashlib
from importlib.metadata import version
import json
import math
from pathlib import Path
import sys

import tts_bridge

REPO = 'pnnbao-ump/VieNeu-TTS-v3-Nano'
REVISION = 'aba295eb96a6fa6003ebe417cc1f2802a7adc1dc'
REQUIRED_FILES = ('text_encoder.onnx', 'duration_predictor.onnx', 'vector_estimator.onnx',
    'codec_decoder.onnx', 'config.json', 'constants.npz')
BACKEND = 'vieneu-nano-cpu-python'


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest()


def _new_engine(artifacts: Path):
    if version('vieneu') != '3.8.3':
        raise RuntimeError('Selected host TTS requires vieneu==3.8.3')
    from vieneu import Vieneu
    engine = Vieneu(mode='v3nano', backbone_repo=str(artifacts), onnx_dir=str(artifacts),
        threads=2, steps=8, hf_token=False)
    if engine.backend != 'onnx' or engine.engine._prov != ['CPUExecutionProvider']:
        raise RuntimeError('Selected host TTS requires ONNX CPU')
    if 'Trúc Ly' not in [name for _, name in engine.list_preset_voices()]:
        raise RuntimeError('Selected Vietnamese preset voice missing')
    return engine


class HostVieneuTts:
    """One resident VI engine for a sequential run; never silently falls back to old VI voice."""
    def __init__(self, models: Path, artifacts: Path, manifest: Path):
        self.models, self.artifacts = models, artifacts
        data = json.loads(manifest.read_text())
        if data.get('repo') != REPO or data.get('revision') != REVISION:
            raise RuntimeError('VieNeu model/revision mismatch')
        rows = data.get('downloaded_files', [])
        if len(rows) != len(REQUIRED_FILES) or {r.get('path') for r in rows} != set(REQUIRED_FILES):
            raise RuntimeError('VieNeu artifact inventory mismatch')
        for row in rows:
            path = artifacts / row['path']
            if not path.is_file() or path.stat().st_size != row['bytes'] or sha256(path) != row['sha256']:
                raise RuntimeError('VieNeu artifact integrity failure: ' + row['path'])
        self.provenance = dict(backend=BACKEND, model=REPO, revision=REVISION,
            sdk='3.8.3', voice='Trúc Ly', steps=8, threads=2, seed=42,
            artifact_manifest_sha256=sha256(manifest), android_validated=False, qnn=False)
        self._engine = None

    def synthesize(self, text: str, language: str, destination: Path):
        if not text.strip() or language not in ('vi', 'en'):
            raise ValueError('Nonblank text and VI/EN language required')
        if language == 'en':
            return tts_bridge.synthesize(self.models, language, text, destination)
        if self._engine is None:
            self._engine = _new_engine(self.artifacts)
        samples = [float(x) for x in self._engine.infer(text, voice='Trúc Ly', steps=8, seed=42)]
        if not samples or not all(math.isfinite(x) for x in samples):
            raise RuntimeError('Empty or non-finite speech audio')
        pcm = array.array('h', (int(max(-1., min(1., x)) * 32767) for x in samples))
        if sys.byteorder != 'little':
            pcm.byteswap()
        tts_bridge._write_pcm16_wav(destination, pcm.tobytes(), 24000)
        return replace(tts_bridge._stats(samples, 24000, destination), backend=BACKEND)


def main() -> None:
    import argparse
    import time
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--models', type=Path, default=Path('models'))
    p.add_argument('--artifacts', type=Path, required=True)
    p.add_argument('--manifest', type=Path, required=True)
    p.add_argument('--requests', type=Path, required=True, help='JSON array of {text, language}')
    p.add_argument('--out-dir', type=Path, required=True)
    a = p.parse_args()
    requests = json.loads(a.requests.read_text())
    if not isinstance(requests, list) or not requests or any(
        not isinstance(r, dict) or not isinstance(r.get('text'), str) or not r['text'].strip()
        or r.get('language') not in ('vi', 'en') for r in requests):
        p.error('Requests must contain nonblank text and VI/EN language')
    engine = HostVieneuTts(a.models, a.artifacts, a.manifest)
    a.out_dir.mkdir(parents=True, exist_ok=False)
    with (a.out_dir / 'results.jsonl').open('w') as log:
        for i, request in enumerate(requests):
            started = time.perf_counter()
            stats = engine.synthesize(request['text'], request['language'], a.out_dir / f'{i}.wav')
            row = dict(index=i, language=request['language'], text=request['text'],
                elapsed_ms=(time.perf_counter() - started) * 1000, **asdict(stats))
            log.write(json.dumps(row, ensure_ascii=False) + '\n')
    (a.out_dir / 'provenance.json').write_text(json.dumps(engine.provenance, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
