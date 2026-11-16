#!/usr/bin/env python3
"""Benchmark the current mel frontend against a pinned Git source using identical filters/input."""
from pathlib import Path
import argparse, subprocess, os, hashlib, json
ROOT=Path(__file__).resolve().parents[3]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--baseline',default='HEAD');p.add_argument('--output',type=Path,default=ROOT/'build/mel-performance');p.add_argument('--filters',type=Path,default=ROOT/'models/speech_to_text/phowhisper-base-onnx/mel_80_201.f32.bin');a=p.parse_args()
out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
source='android/app/src/main/java/com/voxbridge/runtime/MelSpectrogram.kt'
old=subprocess.check_output(['git','show',a.baseline+':'+source],cwd=ROOT,text=True)
(out/'BaselineMelSpectrogram.kt').write_text(old.replace('class MelSpectrogram(', 'class BaselineMelSpectrogram('))
lib=ROOT/'tools/e2e-jvm/.cache/kotlinc-2.0.21/lib';jdk=os.environ.get('JAVA_HOME','/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home')
env=dict(os.environ,JAVA_HOME=jdk,JAVA_OPTS='-Xmx256m')
cmd=[str(lib.parent/'bin/kotlinc'),'-language-version','1.9',str(ROOT/source),str(ROOT/'android/app/src/main/java/com/voxbridge/runtime/RealFft400.kt'),str(out/'BaselineMelSpectrogram.kt'),str(Path(__file__).with_name('MelBenchmark.kt')),'-d',str(out/'benchmark.jar')]
subprocess.run(cmd,check=True,env=env)
r=subprocess.run([jdk+'/bin/java','-Xmx128m','-cp',str(out/'benchmark.jar')+':'+str(lib/'kotlin-stdlib.jar'),'com.voxbridge.runtime.MelBenchmarkKt',str(a.filters.resolve())],capture_output=True,text=True,check=True)
(out/'measurements.log').write_text(r.stdout+r.stderr)
(out/'metadata.json').write_text(json.dumps({'scope':'host_cpu_mel_frontend_only','baseline_commit':subprocess.check_output(['git','rev-parse',a.baseline],cwd=ROOT,text=True).strip(),'baseline_source_sha256':hashlib.sha256(old.encode()).hexdigest(),'candidate_source_sha256':hashlib.sha256((ROOT/source).read_bytes()).hexdigest(),'filter_sha256':hashlib.sha256(a.filters.read_bytes()).hexdigest(),'android_or_neural_inference':False},indent=2))
print(r.stdout)
