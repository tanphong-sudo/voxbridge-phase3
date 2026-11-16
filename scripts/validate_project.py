#!/usr/bin/env python3
from pathlib import Path
import sys
import json
import ast
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = [
    'README.md',
    'docs/README.md',
    'submission/phase-2/README.md',
    'submission/phase-3/README.md',
    'submission/phase-3/templates/template-field-inventory.json',
    'core/src/main/kotlin/com/voxbridge/core/safety/SafetyFrame.kt',
    'core/src/main/kotlin/com/voxbridge/core/safety/SafetyFrameExtractor.kt',
    'core/src/main/kotlin/com/voxbridge/core/safety/SafetyRepairer.kt',
    'core/src/main/kotlin/com/voxbridge/core/translate/Translator.kt',
    'core/src/main/kotlin/com/voxbridge/core/pipeline/DecisionEngine.kt',
    'core/src/main/kotlin/com/voxbridge/core/learn/LearningExample.kt',
    'benchmark-cli/src/main/kotlin/com/voxbridge/benchmark/Main.kt',
    'domain-packs/factory_en_vi/glossary.csv',
    'testdata/manifest.example.csv',
]

missing = [p for p in REQUIRED if not (ROOT / p).exists()]
if missing:
    print('Missing files:')
    for item in missing:
        print('-', item)
    sys.exit(1)

# Resolve the launcher exactly as Android does, rather than checking only file presence.
android_ns = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(ROOT / 'android/app/src/main/AndroidManifest.xml')
for activity in manifest.findall('.//activity'):
    name = activity.attrib[android_ns + 'name']
    qualified = 'com.voxbridge' + name if name.startswith('.') else name
    source = ROOT / 'android/app/src/main/java' / (qualified.replace('.', '/') + '.kt')
    assert source.is_file(), f'Activity source missing: {qualified}'
    assert f'package {qualified.rsplit(".", 1)[0]}' in source.read_text()

for notebook in (ROOT / 'colab').glob('*.ipynb'):
    for index, cell in enumerate(json.loads(notebook.read_text())['cells']):
        if cell['cell_type'] != 'code':
            continue
        code = ''.join(cell['source'])
        label = f'{notebook.name} cell {index}'
        if code.startswith('%%bash\n'):
            subprocess.run(['bash', '-n'], input=code.split('\n', 1)[1], text=True, check=True)
            assert 'set -euo pipefail' in code, f'{label}: shell must fail on errors'
        else:
            tree = ast.parse('\n'.join(line for line in code.splitlines() if not line.startswith('!')), filename=label)
            for node in ast.walk(tree):
                if isinstance(node, ast.Constant) and isinstance(node.value, str):
                    value = node.value
                    if value.startswith(('scripts/', 'model-tools-python/scripts/')) and value.endswith(('.py', '.sh')):
                        assert (ROOT / value).is_file(), f'{label}: missing script {value}'
        assert 'onevoice-guard' not in code, f'{label}: obsolete repo path'

print('Project structure, Android launcher, and notebook static validation passed.')
