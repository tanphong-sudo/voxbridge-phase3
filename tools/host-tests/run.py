#!/usr/bin/env python3
"""Run real Kotlin unit tests on the host with explicit Android/Google boundary fakes; no models."""
from pathlib import Path
import argparse
import os
import re
import shutil
import subprocess
import urllib.request
from adapter_fixture import build as build_adapter_fixture

ROOT = Path(__file__).resolve().parents[2]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT/'build/host-tests')
    parser.add_argument('--callback-ref', help='Test callbacks from a Git revision (regression demonstration only)')
    args = parser.parse_args()
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=True)
    cache = ROOT/'tools/e2e-jvm/.cache'
    lib = cache/'kotlinc-2.0.21/lib'
    compiler = lib.parent/'bin/kotlinc'
    if not compiler.is_file(): parser.error('Cached kotlinc 2.0.21 required; see tools/e2e-jvm/run.sh setup')
    console = cache/'junit-platform-console-standalone-1.10.2.jar'
    if not console.is_file():
        prior = ROOT/'output/phase3/partner-merge-20261015'/console.name
        if prior.is_file(): shutil.copyfile(prior, console)
        else:
            partial = console.with_suffix('.part')
            urllib.request.urlretrieve('https://repo.maven.apache.org/maven2/org/junit/platform/junit-platform-console-standalone/1.10.2/'+console.name, partial)
            partial.replace(console)
    coroutines = cache/'kotlinx-coroutines-core-jvm-1.8.1.jar'
    if not coroutines.is_file():
        partial=coroutines.with_suffix('.part')
        urllib.request.urlretrieve('https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.1/'+coroutines.name,partial)
        partial.replace(coroutines)
    jdk = os.environ.get('JAVA_HOME', '/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home')
    java = str(Path(jdk)/'bin/java') if (Path(jdk)/'bin/java').is_file() else shutil.which('java')
    if not java: parser.error('JDK 17 required; set JAVA_HOME')
    env = dict(os.environ, JAVA_HOME=str(Path(java).parent.parent), JAVA_OPTS='-Xmx1g')
    runtime=ROOT/'android/app/src/main/java/com/voxbridge/runtime'
    ui=ROOT/'android/app/src/main/java/com/voxbridge/ui'
    sources=list((ROOT/'core/src/main/kotlin').rglob('*.kt'))+list((ROOT/'core/src/test/kotlin').rglob('*.kt'))
    tests=list((ROOT/'core/src/test/kotlin').rglob('*Test.kt'))
    for name in ['ModelProfile','ModelPaths','ModelIntegrity','ModelInstallTransaction','ResourceScope','RealFft400','MelSpectrogram','PreparedTranslator','SpmTokenizer','PiperTiles','PiperVocoder','PiperTts']:
        sources.append(runtime/(name+'.kt'))
    sources += [
        ui/'PipelineGates.kt', ui/'RuntimeTeardown.kt', ui/'AudioOut.kt', ui/'ChunkedSpeech.kt',
        ui/'LiveAudioStatus.kt', ui/'PushToTalkHold.kt', ui/'TentativeOutputPresentation.kt', ui/'TurnPresentation.kt', ui/'QuickPhrases.kt',
        ROOT/'android/app/src/main/java/com/voxbridge/audio/AudioQualityTelemetry.kt',
        build_adapter_fixture(ROOT, out/'SpeechAdapterHarness.kt', args.callback_ref),
    ]
    sources += list((ROOT/'tools/host-tests/stubs').rglob('*.kt'))
    sources += list((ROOT/'benchmark-cli/src/main/kotlin').rglob('*.kt'))
    selected=list((ROOT/'android/app/src/test/java/com/voxbridge/runtime').glob('*Test.kt'))+list((ROOT/'android/app/src/test/java/com/voxbridge/ui').glob('*Test.kt'))+list((ROOT/'benchmark-cli/src/test/kotlin').rglob('*Test.kt'))+list((ROOT/'tools/host-tests/test').glob('*Test.kt'))
    selected += [ROOT/'android/app/src/test/java/com/voxbridge/audio/AudioQualityTelemetryTest.kt']
    sources+=selected;tests+=selected
    tokenizer_jars = [cache/name for name in ['sentencepiece4j-1.0.2.jar','protobuf-java-4.32.1.jar','fastutil-8.5.12.jar','json-20240303.jar']]
    if any(not path.is_file() for path in tokenizer_jars):
        parser.error('Tokenizer dependency JARs required; prepare tools/e2e-jvm/run.sh cache first')
    cp=':'.join(map(str,[lib/'kotlin-test.jar',lib/'kotlin-test-junit.jar',coroutines,console,*tokenizer_jars]))
    with (out/'compile.log').open('w') as log:
        result=subprocess.run([str(compiler),'-language-version','1.9',*map(str,sources),'-cp',cp,'-d',str(out/'tests.jar')],env=env,stdout=log,stderr=subprocess.STDOUT)
    if result.returncode:
        print((out/'compile.log').read_text());return result.returncode
    classes=[]
    for path in tests:
        text=path.read_text();package=re.search(r'^package (.+)$',text,re.M).group(1);name=re.search(r'^class (\w+)',text,re.M).group(1);classes.append(package+'.'+name)
    cmd=[java,'-Xmx1g','-cp',str(out/'tests.jar')+':'+cp+':'+str(lib/'kotlin-stdlib.jar'),'org.junit.platform.console.ConsoleLauncher','execute','--disable-banner','--details=summary',*[arg for cls in classes for arg in ['--select-class',cls]]]
    result=subprocess.run(cmd,capture_output=True,text=True)
    (out/'tests.log').write_text(result.stdout+result.stderr);print(result.stdout+result.stderr)
    return result.returncode

if __name__=='__main__':raise SystemExit(main())
