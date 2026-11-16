#!/usr/bin/env python3
"""Placeholder for generating noisy audio manifests.

This skeleton avoids shipping audio processing dependencies. Later this script can mix
clean command audio with factory/logistics noise at target signal-to-noise levels.
"""

from pathlib import Path


def main() -> None:
    root = Path("testdata")
    print("Planned testdata root:", root)
    print("Add clean/noisy/nonspeech audio and update manifest.csv.")


if __name__ == "__main__":
    main()
