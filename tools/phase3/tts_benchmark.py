#!/usr/bin/env python3
"""Paired warm Piper full-text vs production Kotlin sentence plans; synthesis only, no speaker."""
import argparse
import hashlib
import json
import time
from pathlib import Path


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--plan', type=Path, required=True)
    p.add_argument('--models', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    args = p.parse_args()
    import sherpa_onnx
    import numpy as np
    rows = [json.loads(x) for x in args.plan.read_text().splitlines()]
    args.out.mkdir(parents=True, exist_ok=True)
    results = []
    cold = {}
    for lang, bundle in [('en', 'vits-piper-en_US-lessac-medium'), ('vi', 'vits-piper-vi_VN-vais1000-medium')]:
        folder = args.models/'text_to_speech/piper'/bundle
        graph, = folder.glob('*.onnx')
        started = time.perf_counter()
        voice = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
            model=sherpa_onnx.OfflineTtsModelConfig(
                vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(graph), tokens=str(folder/'tokens.txt'),
                    data_dir=str(folder/'espeak-ng-data'), noise_scale=0., noise_scale_w=0., length_scale=1.),
                num_threads=2, provider='cpu')))
        cold[lang] = (time.perf_counter()-started)*1000
        voice.generate('Hello.' if lang == 'en' else 'Xin chào.', sid=0, speed=1.)
        for i, row in enumerate(r for r in rows if r['target_lang'] == lang):
            timings = {}
            lengths = {}
            # Alternate paired order to reduce systematic warm/cache/load bias.
            modes = ['full', 'chunks'] if i % 2 == 0 else ['chunks', 'full']
            for mode in modes:
                texts = [row['spoken_full']] if mode == 'full' else row['chunks']
                start = time.perf_counter()
                first_ms = None
                duration = 0
                for text in texts:
                    audio = voice.generate(text, sid=0, speed=1.)
                    assert len(audio.samples) and audio.sample_rate == 22050
                    if first_ms is None:
                        first_ms = (time.perf_counter()-start)*1000
                    duration += len(audio.samples)/audio.sample_rate
                timings[mode] = dict(first_ms=first_ms, total_ms=(time.perf_counter()-start)*1000,
                                     audio_duration_s=duration)
            cache_ms = None
            if row['command_cache_id']:
                start = time.perf_counter()
                base = args.models/'text_to_speech/cache'
                payload = (base/(row['command_cache_id']+'.pcm')).read_bytes()
                rate = int((base/(row['command_cache_id']+'.sr')).read_text())
                assert payload and rate == 22050
                cache_ms = (time.perf_counter()-start)*1000
            results.append(dict(id=row['id'], model=row['model'], language=lang,
                n_chunks=len(row['chunks']), command_cache_id=row['command_cache_id'], cache_read_ms=cache_ms,
                **timings))
        del voice
    (args.out/'results.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in results))
    def stats(values):
        return dict(p50=float(np.percentile(values, 50)), p95=float(np.percentile(values, 95)))
    summary = dict(n=len(results), cold_voice_load_ms=cold, sherpa=sherpa_onnx.__version__,
                   plan_sha256=hashlib.sha256(args.plan.read_bytes()).hexdigest(), groups={})
    for model in ['envit5','vinai']:
        for lang in ['en','vi']:
            subset = [r for r in results if r['model']==model and r['language']==lang]
            summary['groups'][model+'_'+lang] = dict(n=len(subset), multi_sentence=sum(r['n_chunks']>1 for r in subset),
                cache_hits=sum(r['command_cache_id'] is not None for r in subset),
                full_first_ms=stats([r['full']['first_ms'] for r in subset]),
                chunk_first_ms=stats([r['chunks']['first_ms'] for r in subset]))
    multi = [r for r in results if r['n_chunks']>1]
    summary['multi_sentence'] = dict(n=len(multi), full_first_ms=stats([r['full']['first_ms'] for r in multi]),
        chunk_first_ms=stats([r['chunks']['first_ms'] for r in multi]),
        total_synthesis_ms=stats([r['chunks']['total_ms'] for r in multi])) if multi else dict(n=0)
    (args.out/'summary.json').write_text(json.dumps(summary, indent=2)+'\n')
    print(json.dumps(summary, indent=2))

if __name__ == '__main__':
    main()
