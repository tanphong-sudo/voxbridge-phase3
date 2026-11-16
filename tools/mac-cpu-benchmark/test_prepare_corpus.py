from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from prepare_corpus import (
    FleursRow,
    conversion_record,
    manifest_record,
    mix_at_snr,
    parse_fleurs_tsv,
    read_pcm16,
    source_provenance,
    stratified_select,
    write_edge_fixtures,
    write_jsonl,
    write_pcm16,
)


class PrepareCorpusTest(unittest.TestCase):
    def test_stratified_selection_is_deterministic_and_spans_lengths(self) -> None:
        rows = [FleursRow(str(i), f"{i}.wav", str(i), str(i), i * 100, "X") for i in range(1, 101)]
        first = stratified_select(rows, 50, 7)
        second = stratified_select(rows, 50, 7)
        self.assertEqual([r.filename for r in first], [r.filename for r in second])
        lengths = [r.num_samples for r in first]
        self.assertLess(min(lengths), 2_100)
        self.assertGreater(max(lengths), 8_000)

    def test_noise_mix_hits_target_snr_and_is_reproducible(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            speech = root / "speech.wav"
            noise = root / "noise.wav"
            a = root / "a.wav"
            b = root / "b.wav"
            write_pcm16(speech, [5_000 if i % 2 == 0 else -5_000 for i in range(16_000)])
            write_pcm16(noise, [2_000 if (i // 20) % 2 == 0 else -2_000 for i in range(32_000)])
            measured = mix_at_snr(speech, noise, a, 5.0, 123)
            mix_at_snr(speech, noise, b, 5.0, 123)
            self.assertLessEqual(abs(measured - 5.0), 0.1)
            self.assertEqual(hashlib.sha256(a.read_bytes()).digest(), hashlib.sha256(b.read_bytes()).digest())

    def test_jsonl_preserves_unicode_and_nested_fields(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "manifest.jsonl"
            record = {
                "id": "vi-1",
                "reference_source": "Khẩn cấp: đừng mở van số 7 — đã khóa!",
                "reference_slots": {"negation": True},
            }
            write_jsonl(path, [record])
            loaded = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(loaded, record)

    def test_fleurs_literal_backslash_tab_source_quirk_is_preserved(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "test.tsv"
            path.write_text(
                "1\ta.wav\tRaw\tnormalized\\t g r a p h |\t16000\tMALE\n",
                encoding="utf-8",
            )
            rows = parse_fleurs_tsv(path)
            self.assertEqual(len(rows), 1)
            self.assertEqual(rows[0].normalized, "normalized")
            self.assertEqual(rows[0].num_samples, 16000)

    def test_pcm16_mono_16k_validation_and_edge_rejection_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            records = write_edge_fixtures(root)
            malformed = next(row for row in records if row["id"] == "edge-corrupt-wav")
            wrong_rate = next(row for row in records if row["id"] == "edge-wrong-rate-8k")
            self.assertTrue(malformed["expected_input_rejection"])
            self.assertEqual(malformed["expected_rejection_reason"], "malformed_wav")
            self.assertTrue(wrong_rate["expected_input_rejection"])
            self.assertEqual(wrong_rate["expected_rejection_reason"], "wrong_sample_rate")
            with self.assertRaises(wave.Error):
                read_pcm16(Path(str(malformed["wav"])))
            with self.assertRaises(ValueError):
                read_pcm16(Path(str(wrong_rate["wav"])))

            for row in records:
                if row["expected_input_rejection"]:
                    continue
                rate, samples = read_pcm16(Path(str(row["wav"])))
                self.assertEqual(rate, 16000)
                self.assertTrue(samples)

            boundary = next(row for row in records if row["id"] == "edge-boundary-30s")
            _, boundary_samples = read_pcm16(Path(str(boundary["wav"])))
            self.assertEqual(len(boundary_samples), 30 * 16000)
            noise = next(row for row in records if row["id"] == "edge-verified-synthetic-noise-only-2s")
            self.assertTrue(noise["noise_only_verified"])
            self.assertEqual(noise["verification_basis"], "synthetic-by-construction")

    def test_conversion_record_hashes_source_and_generated_pcm(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "source.wav"
            output = root / "output.wav"
            write_pcm16(source, [100, -100] * 8000)
            write_pcm16(output, [50, -50] * 8000)
            record = conversion_record(source, output)
            self.assertEqual(record["source_sha256"], hashlib.sha256(source.read_bytes()).hexdigest())
            self.assertEqual(record["output_sha256"], hashlib.sha256(output.read_bytes()).hexdigest())
            self.assertEqual(record["format"], "PCM16")
            self.assertEqual(record["channels"], 1)
            self.assertEqual(record["sample_rate_hz"], 16000)

    def test_demand_wav_provenance_is_hashed_without_speech_free_claim(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            raw = Path(tmp)
            noise = raw / "demand" / "STRAFFIC" / "ch01.wav"
            write_pcm16(noise, [250, -250] * 8000)
            provenance = source_provenance(raw)
            item = next(
                artifact
                for artifact in provenance["artifacts"]
                if artifact.get("artifact") == "STRAFFIC/ch01.wav"
            )
            self.assertEqual(item["sha256"], hashlib.sha256(noise.read_bytes()).hexdigest())
            self.assertFalse(item["speech_free_verified"])
            self.assertEqual(item["usage"], "background-noise-source-for-mixing-only")

    def test_fleurs_manifest_keeps_target_and_slots_null(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            wav = Path(tmp) / "sample.wav"
            write_pcm16(wav, [0] * 1600)
            record = manifest_record(
                sample_id="fleurs-test-clean",
                parent_id="fleurs-test",
                split="smoke",
                source_kind="fleurs-human-clean",
                source_lang="vi",
                wav=wav,
                reference_source="Xin chào!",
                exposed=True,
            )
            self.assertIsNone(record["reference_target"])
            self.assertIsNone(record["reference_slots"])


if __name__ == "__main__":
    unittest.main()
