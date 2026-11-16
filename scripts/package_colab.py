"""Build a source-only Colab ZIP with the required voxbridge/ root."""
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'output/colab/voxbridge.zip'
DIRECTORIES = ('android', 'benchmark-cli', 'colab', 'core', 'docs', 'domain-packs',
               'model-tools-python', 'models', 'scripts', 'testdata', 'tools')
EXCLUDED = {'.cache', '__pycache__', 'build', '.gradle', '.git', 'assets', 'libs'}
SUFFIXES = {'.kt', '.kts', '.properties', '.xml', '.py', '.sh', '.md', '.txt',
            '.json', '.csv', '.ipynb', '.yaml', '.yml'}

def package():
    OUT.parent.mkdir(parents=True, exist_ok=True)
    files = [p for name in DIRECTORIES for p in (ROOT / name).rglob('*')
             if p.is_file() and not p.is_symlink() and p.suffix in SUFFIXES
             and not EXCLUDED.intersection(p.relative_to(ROOT).parts)
             and not p.name.startswith(('~$', '.'))]
    files += [p for p in ROOT.iterdir() if p.is_file() and p.suffix in {'.kts', '.properties', '.md', '.sh'}]
    temporary = OUT.with_suffix('.zip.part')
    with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(files):
            archive.write(path, 'voxbridge/' + path.relative_to(ROOT).as_posix())
    with zipfile.ZipFile(temporary) as archive:
        assert archive.testzip() is None
        assert 'voxbridge/settings.gradle.kts' in archive.namelist()
    temporary.replace(OUT)
    print(f'{OUT} ({OUT.stat().st_size:,} bytes, {len(files)} files)')

if __name__ == '__main__':
    package()
