#!/usr/bin/env python3
"""Stage the tested VPS bridge in a fresh directory; never modify the server checkout."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

PACKAGE = Path(__file__).resolve().parent
CORE_PATCH_FILES = tuple('core/src/main/kotlin/com/voxbridge/core/pipeline/'+name
                         for name in ('DecisionEngine.kt','TranslationDecision.kt'))
NEW_HELPERS = tuple('core/src/main/kotlin/com/voxbridge/core/pipeline/'+name
                    for name in ('TentativeOutput.kt','RejectedCaptureDraft.kt'))
SERVER_DIRS = ('core/src/main/kotlin','android/app/src/main/java/com/voxbridge/runtime')
LOG_SHIM = 'tools/e2e-jvm/src/android/util/Log.kt'
MONITOR = 'tools/mac-cpu-benchmark/monitor_process.py'
PACKAGE_FILES = {
    'harness/FullAudioRunner.kt':'source/tools/e2e-jvm/src/FullAudioRunner.kt',
    'tests/ServerTentativeMergeTest.kt':'source/core/src/test/kotlin/com/voxbridge/core/ServerTentativeMergeTest.kt',
    **{'adapter/'+name:'adapter/'+name for name in (
        'PipelineBridge.kt','PcmFiles.kt','PipelineBridgeTest.kt',
        'parakeet_jsonl_worker.py','test_parakeet_jsonl_worker.py')},
    **{name:name for name in ('compile-vps.py','launch-vps.py','score_vps.py','test_score_vps.py')},
}


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def check_hash(path,expected,label):
    if not path.is_file() or sha(path) != expected:
        raise ValueError(label+' SHA256 mismatch: '+str(path))


def copy_file(source,destination):
    destination.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(source,destination)


def stage(server_root,repo_root,work_dir,package_root=PACKAGE):
    server_root,repo_root,work_dir,package_root = map(lambda p:Path(p).resolve(),
                                                    (server_root,repo_root,work_dir,package_root))
    if work_dir.is_relative_to(server_root) or server_root.is_relative_to(work_dir):
        raise ValueError('Work directory must be outside server checkout')
    if work_dir.exists():
        raise ValueError('Refusing existing work directory: '+str(work_dir))
    provenance = json.loads((package_root/'provenance.json').read_text())
    for path in CORE_PATCH_FILES:
        check_hash(server_root/path,provenance['server_base_sha256'][path],'server base')
    for path in NEW_HELPERS:
        check_hash(repo_root/path,provenance['canonical_helpers_sha256'][path],'canonical helper')
    for path in PACKAGE_FILES:
        check_hash(package_root/path,provenance['tested_artifacts_sha256'][path],'tested package artifact')
    check_hash(repo_root/MONITOR,provenance['monitor_process_sha256'],'canonical monitor')
    if not (server_root/LOG_SHIM).is_file() or any(not (server_root/p).is_dir() for p in SERVER_DIRS):
        raise ValueError('Missing server runtime/core/Log shim sources')
    patch = package_root/'server-core.patch'
    work_dir.mkdir(parents=True)
    try:
        source = work_dir/'source'
        for relative in SERVER_DIRS:
            for file in (server_root/relative).rglob('*'):
                if file.is_file() and file.suffix in ('.kt','.java'):
                    if file.is_symlink():
                        raise ValueError('Refusing source symlink: '+str(file))
                    copy_file(file,source/file.relative_to(server_root))
        copy_file(server_root/LOG_SHIM,source/LOG_SHIM)
        # Git applies only in the isolated source tree after both exact base checks.
        subprocess.run(['git','apply','--check',str(patch)],cwd=source,check=True,capture_output=True)
        subprocess.run(['git','apply',str(patch)],cwd=source,check=True,capture_output=True)
        for path in CORE_PATCH_FILES:
            check_hash(source/path,provenance['patched_sha256'][path],'merged core')
        for path in NEW_HELPERS:
            copy_file(repo_root/path,source/path)
        for src,destination in PACKAGE_FILES.items():
            copy_file(package_root/src,work_dir/destination)
        copy_file(repo_root/MONITOR,work_dir/'adapter/monitor_process.py')
        copy_file(package_root/'provenance.json',work_dir/'provenance.json')
        copy_file(patch,work_dir/'server-core.patch')
    except Exception:
        shutil.rmtree(work_dir)
        raise
    return work_dir


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server-root',type=Path,default=Path('/home/opc/voxbridge-current'))
    parser.add_argument('--repo-root',type=Path,required=True,help='canonical feature checkout containing the two new core helpers')
    parser.add_argument('--work-dir',type=Path,required=True,help='fresh isolated directory outside the server checkout')
    args = parser.parse_args()
    try:
        destination = stage(args.server_root,args.repo_root,args.work_dir)
    except (OSError,ValueError,KeyError,subprocess.CalledProcessError) as error:
        parser.error(str(error))
    print('Staged exact tested integration:',destination)


if __name__ == '__main__':
    main()
