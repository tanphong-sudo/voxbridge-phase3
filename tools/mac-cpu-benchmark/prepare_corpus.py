#!/usr/bin/env python3
"""Prepare deterministic Mac CPU benchmark corpora from pinned public sources."""

from __future__ import annotations

import argparse
import array
import hashlib
import json
import math
import os
import random
import shutil
import subprocess
import tarfile
import urllib.request
import wave
import zipfile
from dataclasses import dataclass
from pathlib import Path


FLEURS_REV = "70bb2e84b976b7e960aa89f1c648e09c59f894dd"
FLEURS_BASE = f"https://huggingface.co/datasets/google/fleurs/resolve/{FLEURS_REV}/data"
FLEURS_LICENSE = "CC-BY-4.0"
DEMAND_RECORD = "1227121"
DEMAND_BASE = f"https://zenodo.org/records/{DEMAND_RECORD}/files"
DEMAND_LICENSE = "CC-BY-SA-3.0"
LANGS = ("vi", "en")
LOCALE = {"vi": "vi_vn", "en": "en_us"}
TARGET = {"vi": "en", "en": "vi"}
DEV_PER_LANGUAGE = 50
NOISE_LEVELS = (20, 10, 5, 0)
NOISE_FILES = ("STRAFFIC_16k.zip", "DWASHING_16k.zip", "DKITCHEN_16k.zip")

# SHA-256 values are for the exact upstream artifacts resolved by FLEURS_REV and
# DEMAND_RECORD.  They make an already-populated cache self-verifying offline and
# prevent a mutable download endpoint from silently changing benchmark inputs.
FLEURS_SHA256: dict[tuple[str, str, str], str] = {
    ("en_us", "dev", "tsv"): "9d57ee7e91e9d4c92edb39f6bbea668ef8dc2a3ff96eb510d5580b2ad05d17ec",
    ("en_us", "dev", "audio"): "2658fda72f199e12676ecac9415094667a4e14e149b146e568ea00b2a2f0954c",
    ("en_us", "test", "tsv"): "74c046239374deeb60fa63f258f907388093a32bcaa3140965f70ef05c79f7ca",
    ("en_us", "test", "audio"): "d9c2e37b41aacd41bc283554a0a82b5476b36887049774ecb2819dcaaa55a356",
    ("vi_vn", "dev", "tsv"): "c9bc17cede9765b1c75cb7a608f7066cb6bb6f8245cc75d721a5217a5dffb414",
    ("vi_vn", "dev", "audio"): "8821a394c99069409b3ce7bb5cd14b10b709f2ee2fea16f42b4701e1cc9ef673",
    ("vi_vn", "test", "tsv"): "eb744c2be5f677b49c35527d428a4f647294bd0f67abd790c296e91efaaffb93",
    ("vi_vn", "test", "audio"): "b359261216ef14b8eea159abce794e270c2a0be903218cf1e1e8f7c7c1565b5c",
}
DEMAND_SHA256: dict[str, str] = {
    "STRAFFIC_16k.zip": "2df9b444afb3b789df92d96cb6f58a50653cdbbbe7e7e74fcd9de2af6385fbea",
    "DWASHING_16k.zip": "f687c0806b0e9731b71a9c01c6134a21330efc5fa4aa7ee97cf4df276ad1dae1",
    "DKITCHEN_16k.zip": "9d68e46d2709847c59580770e2f8aa160607ebff7475788174aa1080332cc845",
}


@dataclass(frozen=True)
class FleursRow:
    sentence_id: str
    filename: str
    raw: str
    normalized: str
    num_samples: int
    gender: str


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_fleurs_tsv(path: Path) -> list[FleursRow]:
    rows: list[FleursRow] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            # A small number of upstream FLEURS TSV rows contain a literal two-character
            # ``\\t`` between the normalized transcript and grapheme field rather than a real
            # tab. Normalizing that source quirk keeps the row instead of silently dropping it.
            record = line.rstrip("\n").replace("\\t", "\t").split("\t")
            if len(record) < 7:
                raise ValueError(f"bad FLEURS row in {path}: {record!r}")
            rows.append(
                FleursRow(
                    sentence_id=record[0],
                    filename=record[1],
                    raw=record[2],
                    normalized=record[3],
                    num_samples=int(record[5]),
                    gender=record[6],
                )
            )
    return rows


def stratified_select(rows: list[FleursRow], count: int, seed: int) -> list[FleursRow]:
    if count >= len(rows):
        return list(rows)
    ordered = sorted(rows, key=lambda row: (row.num_samples, row.filename))
    bins = [ordered[i * len(ordered) // 5 : (i + 1) * len(ordered) // 5] for i in range(5)]
    rng = random.Random(seed)
    quotas = [count // 5] * 5
    for i in range(count % 5):
        quotas[i] += 1
    selected: list[FleursRow] = []
    for bucket, quota in zip(bins, quotas):
        chosen = rng.sample(bucket, quota)
        selected.extend(chosen)
    return sorted(selected, key=lambda row: row.filename)


def verify_sha256(path: Path, expected: str) -> None:
    actual = sha256_file(path)
    if actual != expected:
        raise ValueError(f"SHA-256 mismatch for {path}: expected {expected}, got {actual}")


def download(url: str, destination: Path, offline: bool, expected_sha256: str) -> None:
    if destination.is_file() and destination.stat().st_size > 0:
        verify_sha256(destination, expected_sha256)
        return
    if offline:
        raise FileNotFoundError(f"offline cache miss: {destination}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_suffix(destination.suffix + ".part")
    request = urllib.request.Request(url, headers={"User-Agent": "VoxBridge-MacCPU/1"})
    try:
        with urllib.request.urlopen(request, timeout=120) as response, partial.open("wb") as out:
            shutil.copyfileobj(response, out)
        if partial.stat().st_size == 0:
            raise RuntimeError(f"empty download: {url}")
        verify_sha256(partial, expected_sha256)
        partial.replace(destination)
    finally:
        if partial.exists() and not destination.exists():
            partial.unlink()


def ensure_fleurs(raw_root: Path, lang: str, split: str, offline: bool) -> tuple[Path, Path]:
    locale = LOCALE[lang]
    tsv = raw_root / "fleurs" / locale / f"{split}.tsv"
    archive = raw_root / "fleurs" / locale / f"{split}.tar.gz"
    download(
        f"{FLEURS_BASE}/{locale}/{split}.tsv",
        tsv,
        offline,
        FLEURS_SHA256[(locale, split, "tsv")],
    )
    download(
        f"{FLEURS_BASE}/{locale}/audio/{split}.tar.gz",
        archive,
        offline,
        FLEURS_SHA256[(locale, split, "audio")],
    )
    return tsv, archive


def import_existing_test(repo: Path, raw_root: Path, lang: str) -> tuple[Path, Path] | None:
    locale = LOCALE[lang]
    old_tsv = repo / "build" / "real-data" / "fleurs" / f"{locale}-test.tsv"
    old_tar = repo / "build" / "real-data" / "fleurs" / "audio" / f"{locale}-test.tar.gz"
    if not (old_tsv.is_file() and old_tar.is_file()):
        return None
    dest = raw_root / "fleurs" / locale
    dest.mkdir(parents=True, exist_ok=True)
    tsv = dest / "test.tsv"
    archive = dest / "test.tar.gz"
    if not tsv.exists():
        shutil.copy2(old_tsv, tsv)
    if not archive.exists():
        shutil.copy2(old_tar, archive)
    verify_sha256(tsv, FLEURS_SHA256[(locale, "test", "tsv")])
    verify_sha256(archive, FLEURS_SHA256[(locale, "test", "audio")])
    return tsv, archive


def extract_member(archive: Path, split: str, filename: str, destination: Path) -> None:
    if destination.is_file() and destination.stat().st_size > 0:
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    member_name = f"{split}/{filename}"
    with tarfile.open(archive, "r:gz") as tar:
        member = tar.getmember(member_name)
        if not member.isfile():
            raise ValueError(f"not a file in archive: {member_name}")
        source = tar.extractfile(member)
        if source is None:
            raise ValueError(f"cannot extract {member_name}")
        with destination.open("wb") as out:
            shutil.copyfileobj(source, out)


def convert_pcm16(source: Path, destination: Path) -> dict[str, object]:
    if not (destination.is_file() and destination.stat().st_size > 44):
        destination.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(
            [
                "ffmpeg",
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-i",
                str(source),
                "-ar",
                "16000",
                "-ac",
                "1",
                "-c:a",
                "pcm_s16le",
                str(destination),
            ],
            check=True,
        )
    read_pcm16(destination)
    return conversion_record(source, destination)


def read_pcm16(path: Path) -> tuple[int, list[int]]:
    with wave.open(str(path), "rb") as wav:
        if wav.getnchannels() != 1 or wav.getframerate() != 16000 or wav.getsampwidth() != 2:
            raise ValueError(f"expected mono/16k/PCM16: {path}")
        samples = array.array("h")
        samples.frombytes(wav.readframes(wav.getnframes()))
        if os.sys.byteorder != "little":
            samples.byteswap()
        return wav.getframerate(), list(samples)


def write_pcm16(path: Path, samples: list[int], sample_rate: int = 16000) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    data = array.array("h", (max(-32768, min(32767, int(v))) for v in samples))
    if os.sys.byteorder != "little":
        data.byteswap()
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        wav.writeframes(data.tobytes())


def rms(values: list[float]) -> float:
    if not values:
        return 0.0
    return math.sqrt(sum(value * value for value in values) / len(values))


def mix_at_snr(speech_path: Path, noise_path: Path, output: Path, snr_db: float, seed: int) -> float:
    _, speech_i = read_pcm16(speech_path)
    _, noise_i = read_pcm16(noise_path)
    if not speech_i or not noise_i:
        raise ValueError("speech/noise may not be empty")
    rng = random.Random(seed)
    if len(noise_i) >= len(speech_i):
        start = rng.randrange(0, len(noise_i) - len(speech_i) + 1)
        segment_i = noise_i[start : start + len(speech_i)]
    else:
        start = rng.randrange(len(noise_i))
        segment_i = [noise_i[(start + i) % len(noise_i)] for i in range(len(speech_i))]
    speech = [float(value) for value in speech_i]
    noise = [float(value) for value in segment_i]
    speech_rms = rms(speech)
    noise_rms = rms(noise)
    if speech_rms == 0.0 or noise_rms == 0.0:
        raise ValueError("speech/noise RMS may not be zero")
    target_noise_rms = speech_rms / (10.0 ** (snr_db / 20.0))
    scale = target_noise_rms / noise_rms
    scaled_noise = [value * scale for value in noise]
    mixed = [s + n for s, n in zip(speech, scaled_noise)]
    peak = max(abs(value) for value in mixed)
    common_gain = min(1.0, 32760.0 / peak) if peak else 1.0
    output_samples = [round(value * common_gain) for value in mixed]
    write_pcm16(output, output_samples)
    measured = 20.0 * math.log10(rms([v * common_gain for v in speech]) / rms([v * common_gain for v in scaled_noise]))
    return measured


def ensure_demand(raw_root: Path, offline: bool) -> list[tuple[str, Path]]:
    outputs: list[tuple[str, Path]] = []
    for filename in NOISE_FILES:
        archive = raw_root / "demand" / filename
        download(
            f"{DEMAND_BASE}/{filename}?download=1",
            archive,
            offline,
            DEMAND_SHA256[filename],
        )
        stem = filename.removesuffix("_16k.zip")
        dest = raw_root / "demand" / stem / "ch01.wav"
        if not dest.is_file():
            dest.parent.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(archive) as zf:
                member = next(name for name in zf.namelist() if name.endswith("/ch01.wav"))
                with zf.open(member) as source, dest.open("wb") as out:
                    shutil.copyfileobj(source, out)
        read_pcm16(dest)
        outputs.append((stem, dest))
    return outputs


def conversion_record(source: Path, output: Path) -> dict[str, object]:
    sample_rate, samples = read_pcm16(output)
    return {
        "source": str(source.resolve()),
        "source_sha256": sha256_file(source),
        "output": str(output.resolve()),
        "output_sha256": sha256_file(output),
        "format": "PCM16",
        "channels": 1,
        "sample_rate_hz": sample_rate,
        "frames": len(samples),
        "converter": "ffmpeg -ar 16000 -ac 1 -c:a pcm_s16le",
    }


def source_provenance(raw_root: Path) -> dict[str, object]:
    artifacts: list[dict[str, object]] = []
    for locale in ("vi_vn", "en_us"):
        for split in ("dev", "test"):
            for kind, suffix, url in (
                ("tsv", f"{split}.tsv", f"{FLEURS_BASE}/{locale}/{split}.tsv"),
                ("audio", f"{split}.tar.gz", f"{FLEURS_BASE}/{locale}/audio/{split}.tar.gz"),
            ):
                path = raw_root / "fleurs" / locale / suffix
                expected = FLEURS_SHA256[(locale, split, kind)]
                actual = sha256_file(path) if path.is_file() else None
                if actual is not None and actual != expected:
                    raise ValueError(f"SHA-256 mismatch for cached source {path}: expected {expected}, got {actual}")
                artifacts.append(
                    {
                        "source": "FLEURS",
                        "locale": locale,
                        "split": split,
                        "kind": kind,
                        "url": url,
                        "cache_path": str(path.resolve()),
                        "expected_sha256": expected,
                        "sha256": actual,
                        "cached": path.is_file(),
                    }
                )
    for filename in NOISE_FILES:
        path = raw_root / "demand" / filename
        expected = DEMAND_SHA256[filename]
        actual = sha256_file(path) if path.is_file() else None
        if actual is not None and actual != expected:
            raise ValueError(f"SHA-256 mismatch for cached source {path}: expected {expected}, got {actual}")
        artifacts.append(
            {
                "source": "DEMAND",
                "record": DEMAND_RECORD,
                "artifact": filename,
                "url": f"{DEMAND_BASE}/{filename}?download=1",
                "cache_path": str(path.resolve()),
                "expected_sha256": expected,
                "sha256": actual,
                "cached": path.is_file(),
            }
        )
        stem = filename.removesuffix("_16k.zip")
        noise_wav = raw_root / "demand" / stem / "ch01.wav"
        if noise_wav.is_file():
            read_pcm16(noise_wav)
            artifacts.append(
                {
                    "source": "DEMAND",
                    "record": DEMAND_RECORD,
                    "artifact": f"{stem}/ch01.wav",
                    "kind": "extracted-noise-wav",
                    "cache_path": str(noise_wav.resolve()),
                    "sha256": sha256_file(noise_wav),
                    "derived_from": str(path.resolve()),
                    "derived_from_sha256": actual,
                    "cached": True,
                    "speech_free_verified": False,
                    "usage": "background-noise-source-for-mixing-only",
                }
            )
    return {
        "fleurs_revision": FLEURS_REV,
        "fleurs_license": FLEURS_LICENSE,
        "demand_record": DEMAND_RECORD,
        "demand_license": DEMAND_LICENSE,
        "artifacts": artifacts,
    }


def exposed_ids(repo: Path) -> set[str]:
    path = repo / "tools" / "fleurs-audio-regression" / "sample-ids.tsv"
    result: set[str] = set()
    if path.is_file():
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) >= 2:
                result.add(parts[1])
    return result


def manifest_record(
    *,
    sample_id: str,
    parent_id: str,
    split: str,
    source_kind: str,
    source_lang: str,
    wav: Path,
    reference_source: str,
    exposed: bool,
    noise_id: str | None = None,
    snr_db: float | None = None,
    seed: int | None = None,
) -> dict[str, object]:
    return {
        "id": sample_id,
        "parent_id": parent_id,
        "split": split,
        "source_kind": source_kind,
        "source_lang": source_lang,
        "target_lang": TARGET[source_lang],
        "wav": str(wav.resolve()),
        "reference_source": reference_source,
        "reference_target": None,
        "reference_slots": None,
        "allowed_decisions": None,
        "noise_id": noise_id,
        "snr_db": snr_db,
        "seed": seed,
        "exposed": exposed,
        "sha256": sha256_file(wav),
    }


def prepare_fleurs(
    repo: Path,
    data_root: Path,
    tier: str,
    offline: bool,
    with_noise: bool,
) -> tuple[list[dict[str, object]], list[dict[str, object]]]:
    raw_root = data_root / "raw"
    records: list[dict[str, object]] = []
    conversions: list[dict[str, object]] = []
    exposed = exposed_ids(repo)
    noises = ensure_demand(raw_root, offline) if with_noise else []
    for lang_index, lang in enumerate(LANGS):
        official_split = "dev" if tier == "dev" else "test"
        paths = import_existing_test(repo, raw_root, lang) if official_split == "test" else None
        if paths is None:
            paths = ensure_fleurs(raw_root, lang, official_split, offline)
        tsv, archive = paths
        rows = parse_fleurs_tsv(tsv)
        if tier == "smoke":
            smoke_names = exposed_ids(repo)
            rows = [row for row in rows if row.filename in smoke_names]
        elif tier == "dev":
            rows = stratified_select(rows, DEV_PER_LANGUAGE, seed=2718 + lang_index)
        # full uses every test row.
        for index, row in enumerate(rows):
            source = data_root / "source" / LOCALE[lang] / official_split / row.filename
            pcm = data_root / "pcm16" / LOCALE[lang] / official_split / row.filename
            extract_member(archive, official_split, row.filename, source)
            conversions.append(convert_pcm16(source, pcm))
            parent_id = f"fleurs-{LOCALE[lang]}-{official_split}-{row.filename[:-4]}"
            records.append(
                manifest_record(
                    sample_id=f"{parent_id}-clean",
                    parent_id=parent_id,
                    split=tier,
                    source_kind="fleurs-human-clean",
                    source_lang=lang,
                    wav=pcm,
                    reference_source=row.normalized,
                    exposed=row.filename in exposed,
                )
            )
            if with_noise:
                for noise_index, (noise_name, noise_path) in enumerate(noises):
                    for snr in NOISE_LEVELS:
                        seed = 100_000 * lang_index + 1_000 * noise_index + 10 * index + (20 - snr)
                        noisy = data_root / "noisy" / tier / LOCALE[lang] / noise_name / f"{snr}db" / row.filename
                        measured = mix_at_snr(pcm, noise_path, noisy, snr, seed)
                        if abs(measured - snr) > 0.1:
                            raise AssertionError(f"SNR drift {measured:.4f} for {noisy}, target={snr}")
                        records.append(
                            manifest_record(
                                sample_id=f"{parent_id}-{noise_name}-{snr}db",
                                parent_id=parent_id,
                                split=tier,
                                source_kind="fleurs-human-noisy",
                                source_lang=lang,
                                wav=noisy,
                                reference_source=row.normalized,
                                exposed=row.filename in exposed,
                                noise_id=noise_name,
                                snr_db=float(snr),
                                seed=seed,
                            )
                        )
    return records, conversions


def write_jsonl(path: Path, records: list[dict[str, object]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as out:
        for record in records:
            out.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")


def write_edge_fixtures(data_root: Path) -> list[dict[str, object]]:
    edge = data_root / "edge"
    silence = edge / "silence-2s.wav"
    write_pcm16(silence, [0] * 32_000)
    clipped = edge / "clipped-1s.wav"
    write_pcm16(clipped, [32767 if i % 2 == 0 else -32768 for i in range(16_000)])
    too_short = edge / "short-100ms.wav"
    write_pcm16(too_short, [0] * 1_600)
    low_gain = edge / "low-gain-1s.wav"
    write_pcm16(low_gain, [48 if i % 2 == 0 else -48 for i in range(16_000)])
    padded = edge / "leading-trailing-silence-3s.wav"
    write_pcm16(
        padded,
        [0] * 16_000
        + [2_000 if i % 2 == 0 else -2_000 for i in range(16_000)]
        + [0] * 16_000,
    )
    boundary_30s = edge / "boundary-30s.wav"
    write_pcm16(boundary_30s, [1_000 if i % 2 == 0 else -1_000 for i in range(30 * 16_000)])
    verified_noise = edge / "verified-synthetic-noise-only-2s.wav"
    rng = random.Random(90210)
    write_pcm16(verified_noise, [rng.randint(-2_000, 2_000) for _ in range(32_000)])
    wrong_rate = edge / "wrong-rate-8k.wav"
    write_pcm16(wrong_rate, [0] * 8_000, sample_rate=8_000)
    corrupt = edge / "corrupt.wav"
    corrupt.parent.mkdir(parents=True, exist_ok=True)
    corrupt.write_bytes(b"not-a-wave-file\x00\x01")

    def record(
        fixture_id: str,
        parent_id: str,
        kind: str,
        wav: Path,
        source_lang: str,
        allowed: list[str] | None,
        *,
        expected_input_rejection: bool = False,
        expected_rejection_reason: str | None = None,
        noise_only_verified: bool | None = None,
    ) -> dict[str, object]:
        return {
            "id": fixture_id,
            "parent_id": parent_id,
            "split": "edge",
            "source_kind": kind,
            "source_lang": source_lang,
            "target_lang": TARGET[source_lang],
            "wav": str(wav.resolve()),
            "reference_source": "",
            "reference_target": None,
            "reference_slots": {},
            "allowed_decisions": allowed,
            "noise_id": None,
            "snr_db": None,
            "seed": 90210 if noise_only_verified else 0,
            "exposed": False,
            "expected_input_rejection": expected_input_rejection,
            "expected_rejection_reason": expected_rejection_reason,
            "noise_only_verified": noise_only_verified,
            "verification_basis": "synthetic-by-construction" if noise_only_verified else None,
            "sha256": sha256_file(wav),
        }

    return [
        record("edge-silence-2s", "edge-silence", "synthetic-silence", silence, "vi", ["NO_SPEECH"]),
        record("edge-clipped-1s", "edge-clipped", "synthetic-clipped", clipped, "en", ["ASK_REPEAT", "NO_SPEECH"]),
        record("edge-short-100ms", "edge-short", "synthetic-short", too_short, "vi", ["NO_SPEECH", "ASK_REPEAT"]),
        record("edge-low-gain-1s", "edge-low-gain", "synthetic-low-gain", low_gain, "en", ["ASK_REPEAT", "NO_SPEECH"]),
        record("edge-leading-trailing-silence-3s", "edge-leading-trailing-silence", "synthetic-leading-trailing-silence", padded, "vi", ["ASK_REPEAT", "NO_SPEECH"]),
        record("edge-boundary-30s", "edge-boundary-30s", "synthetic-30s-boundary", boundary_30s, "en", ["ASK_REPEAT", "NO_SPEECH"]),
        record(
            "edge-verified-synthetic-noise-only-2s",
            "edge-synthetic-noise-only",
            "synthetic-noise-only",
            verified_noise,
            "vi",
            ["NO_SPEECH"],
            noise_only_verified=True,
        ),
        record(
            "edge-corrupt-wav",
            "edge-corrupt-wav",
            "malformed-wav",
            corrupt,
            "en",
            None,
            expected_input_rejection=True,
            expected_rejection_reason="malformed_wav",
        ),
        record(
            "edge-wrong-rate-8k",
            "edge-wrong-rate",
            "wrong-sample-rate",
            wrong_rate,
            "vi",
            None,
            expected_input_rejection=True,
            expected_rejection_reason="wrong_sample_rate",
        ),
    ]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tier", choices=("smoke", "dev", "full"), required=True)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--data-root", type=Path)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--with-noise", action="store_true")
    args = parser.parse_args()
    repo = args.repo.resolve()
    data_root = (args.data_root or repo / "build" / "mac-cpu-data").resolve()
    records, conversions = prepare_fleurs(repo, data_root, args.tier, args.offline, args.with_noise)
    edge_records = write_edge_fixtures(data_root)
    records.extend(edge_records)
    manifest = data_root / "manifests" / f"{args.tier}.jsonl"
    write_jsonl(manifest, records)
    conversions_path = data_root / "conversion-log.jsonl"
    write_jsonl(conversions_path, conversions)
    provenance = data_root / "provenance.json"
    provenance_data = source_provenance(data_root / "raw")
    provenance_data["conversion_log"] = {
        "path": str(conversions_path.resolve()),
        "sha256": sha256_file(conversions_path),
        "entries": len(conversions),
    }
    provenance.write_text(
        json.dumps(provenance_data, indent=2, sort_keys=True)
        + "\n",
        encoding="utf-8",
    )
    clean = sum(1 for item in records if item["source_kind"] == "fleurs-human-clean")
    noisy = sum(1 for item in records if item["source_kind"] == "fleurs-human-noisy")
    print(f"manifest={manifest}")
    print(f"provenance={provenance}")
    print(f"conversion_log={conversions_path}")
    print(f"records={len(records)} clean={clean} noisy={noisy} edge={len(edge_records)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
