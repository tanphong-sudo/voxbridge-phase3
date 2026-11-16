#!/usr/bin/env python3
"""Read-only adb snapshots during operator-controlled turns; never runs inference."""
import argparse
import csv
import datetime as dt
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


def parse_meminfo(text):
    """Return Android App Summary metrics in KiB, absent metrics stay blank."""
    labels = {"total_pss_kib": "TOTAL PSS", "java_heap_pss_kib": "Java Heap",
              "native_heap_pss_kib": "Native Heap"}
    return {key: (int(match.group(1)) if (match := re.search(
        rf"^\s*{label}:\s*(\d+)", text, re.MULTILINE)) else "")
        for key, label in labels.items()}


def authorized_devices(text):
    return [line.split()[0] for line in text.splitlines()[1:]
            if len(line.split()) >= 2 and line.split()[1] == "device"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True,
                        help="New local directory; logs may contain private utterances")
    parser.add_argument("--turns", type=int, default=100)
    args = parser.parse_args()
    if args.turns < 1:
        parser.error("--turns must be positive")
    adb = shutil.which("adb") or str(Path.home() / "Library/Android/sdk/platform-tools/adb")

    def run(*cmd, optional=False):
        result = subprocess.run([adb, *cmd], capture_output=True, text=True, timeout=30)
        if result.returncode:
            if optional:
                return f"UNAVAILABLE: exit={result.returncode}; {result.stderr.strip()}"
            raise RuntimeError(f"adb failed ({result.returncode}): {result.stderr.strip()}")
        return result.stdout

    devices = authorized_devices(run("devices"))
    if len(devices) != 1:
        raise RuntimeError(f"Expected exactly one authorized device, found {len(devices)}")
    serial = devices[0]
    args.output.mkdir(parents=True, exist_ok=False)
    os.chmod(args.output, 0o700)

    def shell(*cmd, optional=False):
        return run("-s", serial, "shell", *cmd, optional=optional)

    identity = {prop: shell("getprop", prop).strip() for prop in (
        "ro.product.manufacturer", "ro.product.model", "ro.product.cpu.abi",
        "ro.soc.manufacturer", "ro.soc.model", "ro.hardware", "ro.build.fingerprint")}
    identity.update(serial=serial, started_utc=dt.datetime.now(dt.timezone.utc).isoformat(),
                    protocol="manual; CPU/NPU execution must be verified from runtime logs",
                    requested_turns=args.turns)
    (args.output / "identity.json").write_text(json.dumps(identity, indent=2))
    (args.output / "package.txt").write_text(shell("dumpsys", "package", "com.voxbridge"))
    print("No app is started/stopped by this script. Use only synthetic, non-private speech.")
    print("Open app manually if needed. Capture initial startup separately; 'cold' is operator-reported.")
    rows = []
    for turn in range(args.turns + 1):
        prompt = ("Initial snapshot (describe cold/warm state)" if turn == 0 else
                  f"Finish manual turn {turn}/{args.turns}, alternating VI/EN")
        note = input(prompt + "; enter outcome/failure/state note, or q to finish: ").strip()
        if note == "q":
            break
        stamp = dt.datetime.now(dt.timezone.utc).isoformat()
        prefix = args.output / f"{turn:03d}"
        mem = shell("dumpsys", "meminfo", "com.voxbridge")
        for name, value in (("meminfo", mem),
                            ("battery", shell("dumpsys", "battery")),
                            ("thermal", shell("dumpsys", "thermalservice", optional=True)),
                            ("pid", shell("pidof", "com.voxbridge", optional=True))):
            prefix.with_suffix(f".{name}.txt").write_text(value)
        # A process-scoped snapshot avoids collecting other apps' private logs.
        pid = prefix.with_suffix(".pid.txt").read_text().strip().split()
        if len(pid) == 1 and pid[0].isdigit():
            prefix.with_suffix(".logcat.txt").write_text(run(
                "-s", serial, "logcat", "-d", "-v", "threadtime", f"--pid={pid[0]}", "-t", "1000"))
        rows.append(dict(turn=turn, captured_utc=stamp, note=note, **parse_meminfo(mem)))
        with (args.output / "samples.csv").open("w", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)
    print(f"Recorded {max(0, len(rows)-1)} operator-marked turns: {args.output}")
    print("No automatic pass/fail or inference latency inferred. Review raw dumps and notes.")


if __name__ == "__main__":
    main()
