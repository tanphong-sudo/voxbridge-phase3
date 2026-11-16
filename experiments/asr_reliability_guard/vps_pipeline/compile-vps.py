from pathlib import Path
import hashlib,json,os,subprocess,zipfile

base=Path(__file__).resolve().parent
source=base/'source'
runtime=source/'android/app/src/main/java/com/voxbridge/runtime'
cache=Path('/home/opc/voxbridge/tools/e2e-jvm/.cache')
java_home=Path('/usr/lib/jvm/java-17-openjdk')
classes=base/'classes'
assert not classes.exists(), 'Refusing stale compiled classes'
classes.mkdir()
java_classes=base/'java-classes'
java_classes.mkdir(exist_ok=False)
jars=sorted(cache.glob('*.jar'))
cp=os.pathsep.join(map(str,jars))
java_sources=[runtime/(name+'.java') for name in ['ParakeetEngine','ParakeetMel']]
kt=list((source/'core/src/main/kotlin').rglob('*.kt'))
kt=[p for p in kt if not p.name.startswith('._')]
kt += [runtime/(name+'.kt') for name in ['MelSpectrogram','RealFft400','ResourceScope','ModelProfile',
    'ModelPaths','ModelIntegrity','SileroVad','WhisperAsr','WhisperBpeDecoder','ParakeetAsr']]
kt += [source/'tools/e2e-jvm/src/android/util/Log.kt',source/'tools/e2e-jvm/src/FullAudioRunner.kt',
       base/'adapter/PipelineBridge.kt',base/'adapter/PcmFiles.kt']
env=dict(os.environ,JAVA_HOME=str(java_home),JAVA_OPTS='-Xmx512m')
with (base/'compile.log').open('w') as log:
    subprocess.run([str(java_home/'bin/javac'),'-source','17','-target','17','-cp',cp,'-d',str(java_classes),
                    *map(str,java_sources)],check=True,env=env,stdout=log,stderr=subprocess.STDOUT)
    subprocess.run([str(cache/'kotlinc-2.0.21/bin/kotlinc'),'-language-version','1.9','-jvm-target','17',
                    *map(str,kt),'-cp',str(java_classes)+os.pathsep+cp,'-d',str(classes)],
                   check=True,env=env,stdout=log,stderr=subprocess.STDOUT)
paths=java_sources+kt
receipt={str(p.relative_to(base)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
with zipfile.ZipFile(base/'compiled-source.zip','w',zipfile.ZIP_DEFLATED) as archive:
    for p in paths:archive.write(p,str(p.relative_to(base)))
(base/'compiled-source-sha256.json').write_text(json.dumps(receipt,indent=2)+'\n')
runtime_cp=os.pathsep.join([str(classes),str(java_classes),str(cache/'kotlinc-2.0.21/lib/kotlin-stdlib.jar'),cp])
(base/'classpath.txt').write_text(runtime_cp)
print('Fresh VPS compilation passed:',len(paths),'source files',flush=True)
