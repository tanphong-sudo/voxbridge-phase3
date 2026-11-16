#!/usr/bin/env python3
"""Fail when a tracked text file appears to contain a credential."""
from __future__ import annotations

from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PATTERNS = (
    re.compile(r"\bghp_[A-Za-z0-9]{20,}\b"),
    re.compile(r"\bgithub_pat_[A-Za-z0-9_]{20,}\b"),
    re.compile(r"\bhf_[A-Za-z0-9]{20,}\b"),
    re.compile(r"\b(?:QAIHUB_API_TOKEN|HUGGINGFACE_HUB_TOKEN)\s*=\s*(?![<$])[\"']?[^\s\"']+", re.I),
)
EXCLUDED_SUFFIXES = {'.docx', '.pdf', '.xlsx', '.png', '.jpg', '.jpeg', '.onnx', '.apk', '.aab'}


def main() -> int:
    names = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
    findings: list[str] = []
    for name in names:
        path = ROOT / name
        if not name or path.suffix.lower() in EXCLUDED_SUFFIXES:
            continue
        if not path.is_file():
            # A rename in a dirty worktree appears as an index deletion until the new path is
            # staged. The later staged-index scan covers the final committed tree.
            continue
        try:
            text = path.read_text()
        except UnicodeDecodeError:
            continue
        for line_number, line in enumerate(text.splitlines(), start=1):
            if '<YOUR_' in line or '<YOUR-' in line:
                continue
            if any(pattern.search(line) for pattern in PATTERNS):
                findings.append(f'{name}:{line_number}')
    if findings:
        print('Potential credential found in tracked source:', *findings, sep='\n- ')
        return 1
    print('No credential patterns found in tracked text files.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
