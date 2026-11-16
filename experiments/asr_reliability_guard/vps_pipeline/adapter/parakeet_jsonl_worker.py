#!/usr/bin/env python3
"""Persistent, offline CPU bridge to the EXISTING frozen VPS Parakeet v2 runtime.

No dataset/manifest/reference reader, audio processing, alternate decoder or download.
Only stdout JSONL is protocol; native dependency output is redirected to stderr.
"""
import argparse
import contextlib
from enum import Enum
import hashlib
import importlib.metadata
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import time
import traceback
import wave

FROZEN = Path('/home/opc/parakeet-next-20261114/inference')
PIN = Path('/home/opc/en-asr-new3-20261113/nemo/parakeet-v2/model-pin.json')
EXPECTED_MODEL = 'nvidia/parakeet-tdt-0.6b-v2'
EXPECTED_REVISION = 'ae9ad07059c7c739ffaf932226a8fe64ae2620b0'
EXPECTED_CHECKPOINT = 'd99e39955c9d3d0350d8fb7c75e40c64a2b2eaeb003883d7c941fd2e8747b28c'
EXPECTED_SOURCE = {
    'run.py': 'beeea363ebb5cd96f7a7badd4fd5920416049e97fb7110d59e09f1401e732366',
    'contract.py': '09212bc3390fcad5850bfc92055e2ff24db0cf60d60dfe65fd3f566b82fb95ed',
}
EXPECTED_VERSIONS = {'torch': '2.8.0+cpu', 'nemo_toolkit': '2.5.3', 'numpy': '1.26.4'}
BACKEND = 'NeMo2.5.3/PyTorch CPU Parakeet-TDT-v2 FP32 native greedy_batch'
MAX_LINE_BYTES = 65536


def digest(path):
    result = hashlib.sha256()
    with Path(path).open('rb') as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def parse_request(value):
    if not isinstance(value, dict):
        raise ValueError('Request must be an object')
    if set(value) - {'id', 'op', 'wav', 'sha256'}:
        raise ValueError('Unsupported request fields; references/text are forbidden')
    if value.get('op') != 'asr':
        raise ValueError('Only op=asr is supported')
    if not isinstance(value.get('id'), str) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,128}',value['id']):
        raise ValueError('Invalid request id')
    if not isinstance(value.get('wav'),str) or not Path(value['wav']).is_absolute():
        raise ValueError('wav must be an absolute path')
    if 'sha256' in value and (not isinstance(value['sha256'],str) or not re.fullmatch(r'[0-9a-f]{64}',value['sha256'])):
        raise ValueError('Invalid sha256')
    return dict(value)


def checked_audio(request, audio_root, max_samples):
    path = Path(request['wav']).resolve(strict=True)
    if not path.is_relative_to(Path(audio_root).resolve(strict=True)):
        raise ValueError('WAV is outside audio root')
    if not path.is_file() or path.stat().st_size > max_samples * 2 + 65536:
        raise ValueError('Invalid WAV file size')
    # One bounded byte read binds the hash to the exact decoded PCM, even if the
    # caller later replaces the source file. No samples are altered by this bridge.
    with path.open('rb') as source:
        payload = source.read(max_samples * 2 + 65537)
    if len(payload) > max_samples * 2 + 65536:
        raise ValueError('Invalid WAV file size')
    actual = hashlib.sha256(payload).hexdigest()
    if 'sha256' in request and actual != request['sha256']:
        raise ValueError('WAV checksum mismatch')
    import io
    with wave.open(io.BytesIO(payload),'rb') as handle:
        if (handle.getframerate(),handle.getnchannels(),handle.getsampwidth(),handle.getcomptype()) != (16000,1,2,'NONE'):
            raise ValueError('Expected mono PCM16 16kHz WAV')
        count = handle.getnframes()
        if not 0 < count <= max_samples:
            raise ValueError('Invalid WAV sample count')
        pcm = handle.readframes(count)
        if len(pcm) != count * 2:
            raise ValueError('WAV payload is truncated')
    return pcm, count, actual


class NativeRuntime:
    def __init__(self):
        os.environ.update(OMP_NUM_THREADS='4',MKL_NUM_THREADS='4',OPENBLAS_NUM_THREADS='1',
                          NUMBA_NUM_THREADS='4',HF_HUB_OFFLINE='1',TRANSFORMERS_OFFLINE='1')
        for name, expected in EXPECTED_SOURCE.items():
            if digest(FROZEN/name) != expected:
                raise ValueError('Frozen native runtime source changed: '+name)
        versions = {name: importlib.metadata.version(name) for name in EXPECTED_VERSIONS}
        if versions != EXPECTED_VERSIONS:
            raise ValueError('Existing NeMo environment version mismatch: '+str(versions))
        pin = json.loads(PIN.read_text())
        if (pin['model_id'],pin['revision'],pin['file_sha256']) != (EXPECTED_MODEL,EXPECTED_REVISION,EXPECTED_CHECKPOINT):
            raise ValueError('Frozen model pin changed')
        if digest(pin['path']) != EXPECTED_CHECKPOINT:
            raise ValueError('Frozen checkpoint checksum mismatch')
        import numpy as np
        import torch
        torch.set_num_threads(4)
        torch.set_num_interop_threads(1)
        torch.manual_seed(0)
        np.random.seed(0)
        import nemo.collections.asr as nemo_asr
        from omegaconf import OmegaConf, open_dict
        sys.path.insert(0,str(FROZEN))
        spec = importlib.util.spec_from_file_location('frozen_parakeet_runtime',FROZEN/'run.py')
        native = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(native)
        begin = time.perf_counter()
        model = nemo_asr.models.ASRModel.restore_from(pin['path'],map_location=torch.device('cpu'))
        self.model = model.to(device='cpu',dtype=torch.float32).eval()
        self.decoder = native.configure(self.model,'greedy_trim',OmegaConf,open_dict)
        if self.model.preprocessor.featurizer.pad_to != 0:
            raise ValueError('Native frontend pad_to differs from frozen baseline')
        self.torch,self.np,self.native = torch,np,native
        self.provenance = {
            'ready':True,'backend':BACKEND,'model_id':EXPECTED_MODEL,'revision':EXPECTED_REVISION,
            'checkpoint_sha256':EXPECTED_CHECKPOINT,'model_load_ms':(time.perf_counter()-begin)*1000,
            'versions':versions,'precision':'FP32','decoder':self.decoder,
            'native_source_sha256':dict(EXPECTED_SOURCE),'adapter_sha256':digest(__file__),
            'intra_threads':torch.get_num_threads(),'inter_threads':torch.get_num_interop_threads(),
            'audio_processing':'provided mono PCM16 WAV only; no gain/padding/trim/resample',
            'score_semantics':'native cumulative sequence logscore, not calibrated confidence',
            'device_claim':'CPU functional bridge; no QNN parity or device latency claim',
        }

    def transcribe(self, pcm):
        audio = self.np.frombuffer(pcm,dtype='<i2').astype(self.np.float32)/32768.0
        begin = time.perf_counter()
        with self.torch.inference_mode():
            native = self.model.transcribe(audio=[audio],batch_size=1,return_hypotheses=True,
                                           num_workers=0,verbose=False)
        elapsed = (time.perf_counter()-begin)*1000
        nbest = self.native.extract(native,self.decoder['blank_id'])
        return {'text':nbest[0]['text'],'nbest':nbest,'asr_ms':elapsed,'backend':BACKEND,
                'confidence':None,'avg_log_prob':None,'no_speech_probability':None,
                'score_semantics':'native sequence scores; not calibrated confidence'}


def encode_protocol_enum(value):
    # NeMo/OmegaConf retains Enum objects in decoder provenance. Serialize only
    # their stable names; never stringify arbitrary unsupported native objects.
    if isinstance(value,Enum):
        return value.name
    raise TypeError('Unsupported protocol object: '+type(value).__name__)


def write_response(output,value):
    output.write(json.dumps(value,allow_nan=False,default=encode_protocol_enum)+'\n')
    output.flush()


def serve(incoming,outgoing,runtime,audio_root,max_samples):
    while True:
        line = incoming.readline(MAX_LINE_BYTES+1)
        if not line:
            return
        request = None
        try:
            if len(line) > MAX_LINE_BYTES:
                # Drain only this malformed message before accepting the next.
                while not line.endswith('\n'):
                    line = incoming.readline(MAX_LINE_BYTES+1)
                    if not line: break
                raise ValueError('Request line is too long')
            request = parse_request(json.loads(line))
            pcm,count,sha = checked_audio(request,audio_root,max_samples)
            with contextlib.redirect_stdout(sys.stderr):
                result = runtime.transcribe(pcm)
            response = {**result,'ok':True,'id':request['id'],'input_samples':count,'wav_sha256':sha}
            write_response(outgoing,response)
        except Exception as exc:
            traceback.print_exc(file=sys.stderr)
            write_response(outgoing,{'ok':False,'id':request['id'] if request else None,
                                     'error':type(exc).__name__+': '+str(exc)})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--audio-root',type=Path,required=True,
                        help='existing directory containing caller-created canonical PCM16 WAVs')
    parser.add_argument('--max-seconds',type=int,default=30,choices=range(1,61),metavar='1..60')
    args = parser.parse_args()
    if not args.audio_root.is_dir():
        parser.error('--audio-root must be an existing directory')
    with contextlib.redirect_stdout(sys.stderr):
        runtime = NativeRuntime()
    write_response(sys.stdout,runtime.provenance)
    serve(sys.stdin,sys.stdout,runtime,args.audio_root,args.max_seconds*16000)


if __name__ == '__main__':
    main()
