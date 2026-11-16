#!/usr/bin/env python3
"""Verify/fetch a frozen Qualcomm profile candidate; never silently enable it in Android."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest()


def verify_bundle(root, manifest, runtime_version=None, soc=None):
    if runtime_version is not None and runtime_version != manifest['required_ort']:
        raise ValueError(f"ORT mismatch: candidate requires {manifest['required_ort']}, supplied {runtime_version}")
    if soc is not None and soc.casefold() != manifest['soc'].casefold():
        raise ValueError(f"SoC mismatch: candidate requires {manifest['soc']}, supplied {soc}")
    for row in manifest['files']:
        relative = Path(row['path'])
        path = root / relative
        if len(relative.parts) != 1 or relative.is_absolute() or not path.resolve().is_relative_to(root.resolve()):
            raise ValueError('Unsafe artifact path')
        if not path.is_file() or path.stat().st_size != row['bytes'] or digest(path) != row['sha256']:
            raise ValueError('Artifact integrity failure: ' + row['path'])
    metadata = json.loads((root / 'metadata.json').read_text())
    for key in ['model_id', 'runtime', 'precision']:
        if metadata.get(key) != manifest[key]:
            raise ValueError('Metadata mismatch: ' + key)
    versions = metadata.get('tool_versions', {})
    if versions.get('onnx_runtime') != manifest['required_ort'] or versions.get('qairt') != manifest['required_qairt']:
        raise ValueError('Metadata runtime toolchain mismatch')
    return dict(status='profile_candidate_verified', model_id=manifest['model_id'], soc=manifest['soc'],
        required_ort=manifest['required_ort'], required_qairt=manifest['required_qairt'],
        runtime_compatible=runtime_version is not None and soc is not None,
        android_runtime_validated=False, quality_validated=False)


def fetch_bundle(destination, manifest):
    if destination.exists():
        raise ValueError('Destination exists; verify it instead of overwriting')
    destination.parent.mkdir(parents=True, exist_ok=True)
    size = int(manifest['archive']['bytes'])
    required = size + sum(row['bytes'] for row in manifest['files']) + 256 * 1024 * 1024
    if shutil.disk_usage(destination.parent).free < required:
        raise ValueError(f'Insufficient free disk space: need at least {required} bytes for safe staging')
    with tempfile.TemporaryDirectory(prefix='qnn-candidate-', dir=destination.parent) as tmp:
        staging = Path(tmp)
        archive = staging / 'download.zip'
        with urllib.request.urlopen(manifest['archive']['url'], timeout=60) as response, archive.open('wb') as stream:
            while chunk := response.read(1024 * 1024):
                stream.write(chunk)
        if archive.stat().st_size != size or digest(archive) != manifest['archive']['sha256']:
            raise ValueError('Archive integrity failure')
        payload = staging / 'payload'
        payload.mkdir()
        expected = {row['path']: row for row in manifest['files']}
        seen = set()
        with zipfile.ZipFile(archive) as source:
            for info in source.infolist():
                if info.is_dir():
                    continue
                name = Path(info.filename).name
                if name not in expected or name in seen or info.file_size != expected[name]['bytes']:
                    raise ValueError('Unexpected archive artifact: ' + info.filename)
                seen.add(name)
                with source.open(info) as src, (payload / name).open('wb') as dst:
                    shutil.copyfileobj(src, dst)
        verify_bundle(payload, manifest)
        payload.replace(destination)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--manifest', type=Path, default=ROOT / 'config/models/qnn-whisper-small-sm8650.json')
    p.add_argument('--bundle-dir', type=Path, required=True)
    p.add_argument('--download', action='store_true', help='Fetch pinned public release into a new directory')
    p.add_argument('--runtime-version', help='Explicit compatibility check; current Android 1.20.0 must fail')
    p.add_argument('--soc', help='Expected target SoC, e.g. sm8650')
    a = p.parse_args()
    manifest = json.loads(a.manifest.read_text())
    if a.download:
        fetch_bundle(a.bundle_dir, manifest)
    result = verify_bundle(a.bundle_dir, manifest, a.runtime_version, a.soc)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
