#!/usr/bin/env python3
"""Verify the eight rendered command caches against same-voice live PCM; save listenable WAVs."""
import argparse
import hashlib
import json
import sys
import time
import wave
from pathlib import Path


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--repo', type=Path, required=True)
    p.add_argument('--models', type=Path, required=True)
    p.add_argument('--cache', type=Path, required=True)
    args=p.parse_args()
    sys.path.insert(0, str(args.repo/'model-tools-python/scripts'))
    import paths
    paths.TTS_PIPER=args.models/'text_to_speech/piper'
    import render_safety_cache as renderer
    import numpy as np
    output=[]
    for pid,(lang,text) in renderer.PHRASES.items():
        pcm=args.cache/(pid+'.pcm')
        if not pcm.is_file(): continue
        voice=renderer._engine(lang)
        voice.generate(text, sid=0, speed=1.)
        live=[]; reads=[]
        for _ in range(5):
            start=time.perf_counter();audio=voice.generate(text,sid=0,speed=1.)
            live.append((time.perf_counter()-start)*1000)
            expected=np.asarray([int(max(-1.,min(1.,float(s)))*32767) for s in audio.samples], dtype='<i2').tobytes()
            start=time.perf_counter();payload=pcm.read_bytes();rate=int(pcm.with_suffix('.sr').read_text())
            reads.append((time.perf_counter()-start)*1000)
            assert payload == expected and rate == audio.sample_rate
        with wave.open(str(pcm.with_suffix('.wav')),'wb') as w:
            w.setnchannels(1);w.setsampwidth(2);w.setframerate(rate);w.writeframes(payload)
        output.append(dict(id=pid,text=text,sample_rate=rate,bytes=len(payload),sha256=hashlib.sha256(payload).hexdigest(),
            live_p50_ms=float(np.median(live)),read_p50_ms=float(np.median(reads)),pcm_parity=True))
    assert len(output)==8
    (args.cache/'verification.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(output,ensure_ascii=False,indent=2))

if __name__=='__main__':main()
