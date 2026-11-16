from __future__ import annotations

import sys
import unittest
import contextlib
import io
import json
import tempfile
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from score import (
    ErrorAccumulator,
    corpus_bleu,
    corpus_chrf,
    edit_distance,
    normalize_text,
    score_rows,
    tokens,
    vi_characters,
    main,
)


class ScoreTest(unittest.TestCase):
    def test_urgency_boolean_labels_match_runtime_enum_without_false_alarm(self) -> None:
        for expected, actual, unsafe in ((True, "HIGH", 0), (False, "NORMAL", 0),
                                         (True, "NORMAL", 1), (False, "HIGH", 1),
                                         (True, True, 0), (False, False, 0),
                                         (False, None, 1), (False, 0, 1), (True, "urgent", 1)):
            with self.subTest(expected=expected, actual=actual):
                result = score_rows(
                    [{"id": "case", "reference_slots": {"urgency": expected}}],
                    [{"manifest_id": "case", "terminal_decision": "VERIFIED_TRANSLATION",
                      "spoken_allowed": True, "final_slots": {"urgency": actual}}])
                self.assertEqual(result["safety"]["unsafe_spoken_output"], unsafe)
                self.assertEqual(result["safety"]["slot_correct"], 1 - unsafe)

    def test_false_repair_uses_same_urgency_representation_mapping(self) -> None:
        result = score_rows(
            [{"id": "case", "reference_slots": {"urgency": True}}],
            [{"manifest_id": "case", "terminal_decision": "REPAIRED_TRANSLATION",
              "spoken_allowed": False, "final_slots": {"urgency": "HIGH"},
              "pre_repair_slots": {"urgency": "HIGH"}}])
        self.assertEqual(result["safety"]["false_repair"], 1)

    def test_urgency_mapping_does_not_reinterpret_other_slot_values(self) -> None:
        result = score_rows(
            [{"id": "case", "reference_slots": {"action": True}}],
            [{"manifest_id": "case", "terminal_decision": "VERIFIED_TRANSLATION",
              "spoken_allowed": True, "final_slots": {"action": "HIGH"}}])
        self.assertEqual(result["safety"]["unsafe_spoken_output"], 1)

    def test_edit_distance_covers_insertion_deletion_substitution(self) -> None:
        self.assertEqual(edit_distance(["a", "b"], ["a", "x", "b"]), 1)
        self.assertEqual(edit_distance(["a", "b"], ["a"]), 1)
        self.assertEqual(edit_distance(["a", "b"], ["a", "x"]), 1)

    def test_unicode_normalization_and_vietnamese_cer(self) -> None:
        composed = "DỪNG máy số 7!"
        decomposed = "Dư\u0300ng ma\u0301y so\u0302\u0301 7"
        self.assertEqual(tokens(composed), tokens(decomposed))
        self.assertEqual(vi_characters(composed), vi_characters(decomposed))
        self.assertEqual(normalize_text("  STOP—machine!!!  "), "stop machine")

    def test_empty_reference_does_not_create_division_by_zero(self) -> None:
        acc = ErrorAccumulator()
        acc.add([], ["hallucination"])
        self.assertIsNone(acc.value())
        self.assertEqual(acc.empty_reference_samples, 1)
        self.assertEqual(acc.reference_units, 0)

    def test_identical_verified_targets_score_perfectly(self) -> None:
        refs = ["Stop machine number seven now.", "Do not open the valve."]
        self.assertAlmostEqual(corpus_bleu(refs, refs), 100.0)
        self.assertAlmostEqual(corpus_chrf(refs, refs), 100.0)
        degraded = ["Stop machine seven.", "Open the valve."]
        self.assertLess(corpus_chrf(refs, degraded), 100.0)

    def test_verified_target_gate_and_reference_vs_asr_mt_are_separate(self) -> None:
        manifest = [
            {
                "id": "verified",
                "source_lang": "en",
                "snr_db": None,
                "reference_source": "stop machine seven now",
                "reference_target": "dừng máy số bảy ngay",
                "human_verified": True,
                "reference_slots": None,
            },
            {
                "id": "provisional",
                "source_lang": "en",
                "snr_db": None,
                "reference_source": "close the door",
                "reference_target": "đóng cửa",
                "human_verified": False,
                "reference_slots": None,
            },
        ]
        results = [
            {
                "type": "sample",
                "manifest_id": "verified",
                "raw_transcript": "stop machine seven now",
                "terminal_decision": "MODEL_TRANSLATION",
                "final_text": "dừng máy số bảy ngay",
                "mt_from_reference_text": "dừng máy số bảy ngay",
                "mt_from_asr_text": "dừng máy số bảy ngay",
            },
            {
                "type": "sample",
                "manifest_id": "provisional",
                "raw_transcript": "close door",
                "terminal_decision": "MODEL_TRANSLATION",
                "final_text": "đóng cửa",
                "mt_from_reference_text": "đóng cửa",
                "mt_from_asr_text": "đóng cửa",
            },
        ]
        summary = score_rows(manifest, results)
        self.assertEqual(summary["mt"]["reference_transcript"]["human_verified_samples"], 1)
        self.assertEqual(summary["mt"]["asr_transcript"]["human_verified_samples"], 1)
        self.assertEqual(summary["mt"]["reference_vs_asr_path"]["samples"], 2)

    def test_safety_spoken_policy_and_memory_metrics(self) -> None:
        manifest = [
            {
                "id": "unsafe",
                "source_lang": "vi",
                "snr_db": None,
                "reference_source": "Đừng mở van số 7.",
                "reference_target": "Do not open valve 7.",
                "human_verified": True,
                "reference_slots": {"action": "OPEN", "negation": True, "objectId": "7"},
                "allowed_decisions": ["VERIFIED_TRANSLATION", "REPAIRED_TRANSLATION", "ASK_REPEAT"],
            },
            {
                "id": "repair",
                "source_lang": "en",
                "snr_db": 5.0,
                "reference_source": "Do not open valve seven.",
                "reference_target": "Đừng mở van số bảy.",
                "human_verified": True,
                "reference_slots": {"action": "OPEN", "negation": True, "objectId": "7"},
                "allowed_decisions": ["VERIFIED_TRANSLATION", "REPAIRED_TRANSLATION", "ASK_REPEAT"],
            },
        ]
        results = [
            {
                "manifest_id": "unsafe",
                "raw_transcript": "Đừng mở van số 7.",
                "terminal_decision": "VERIFIED_TRANSLATION",
                "final_text": "Open valve seven.",
                "final_slots": {"action": "OPEN", "negation": False, "objectId": "7"},
                "spoken_allowed": True,
                "memory_eligible": True,
                "memory_accepted": True,
                "memory_edit_rate": 0.1,
            },
            {
                "manifest_id": "repair",
                "raw_transcript": "Do not open valve seven.",
                "terminal_decision": "REPAIRED_TRANSLATION",
                "final_text": "Đừng mở van số bảy.",
                "final_slots": {"action": "OPEN", "negation": True, "objectId": "7"},
                "pre_repair_slots": {"action": "OPEN", "negation": True, "objectId": "7"},
                "spoken_allowed": True,
                "memory_eligible": True,
                "memory_accepted": False,
                "memory_source_text": "do not open valve seven",
                "memory_query_text": "do not open valve 7",
            },
        ]
        summary = score_rows(manifest, results)
        self.assertEqual(summary["safety"]["unsafe_spoken_output"], 1)
        self.assertEqual(summary["safety"]["false_repair"], 1)
        self.assertEqual(summary["safety"]["repair_auto_spoken"], 1)
        self.assertEqual(summary["memory"]["eligible"], 2)
        self.assertEqual(summary["memory"]["accepted"], 1)
        self.assertEqual(summary["memory"]["edit_rate_samples"], 2)
        failures = {row["id"]: row for row in summary["failures"]}
        self.assertIn("unsafe_spoken_output", failures["unsafe"]["gate_failures"])
        self.assertIn("repair_auto_spoken", failures["repair"]["gate_failures"])

    def test_cli_exports_real_jsonl_and_fails_on_unsafe_or_missing_results(self) -> None:
        manifest = [{"id": "case", "source_lang": "vi", "reference_slots": {"negation": True}}]
        unsafe = [{"manifest_id": "case", "terminal_decision": "VERIFIED_TRANSLATION",
                   "final_slots": {"negation": False}, "spoken_allowed": True}]
        safe = [{"manifest_id": "case", "terminal_decision": "VERIFIED_TRANSLATION",
                 "final_slots": {"negation": True}, "spoken_allowed": True}]
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            source, results, failures = (directory / name for name in ("manifest.jsonl", "results.jsonl", "failures.jsonl"))
            source.write_text("\n".join(json.dumps(row) for row in manifest) + "\n")
            for rows, expected_exit, expected_failure_count in [(unsafe, 1, 1), (safe, 0, 0), ([], 1, 0)]:
                results.write_text("\n".join(json.dumps(row) for row in rows))
                with patch.object(sys, "argv", ["score.py", "--manifest", str(source), "--results", str(results),
                                                "--failures", str(failures)]), contextlib.redirect_stdout(io.StringIO()):
                    actual_exit = main()
                self.assertEqual(actual_exit, expected_exit)
                exported = [json.loads(line) for line in failures.read_text().splitlines()]
                self.assertEqual(len(exported), expected_failure_count)
                self.assertTrue(all(isinstance(row, dict) for row in exported))

    def test_reject_speech_is_listed_with_runtime_failure_without_duplicate_row(self) -> None:
        summary = score_rows(
            [{"id": "case", "reference_slots": {"negation": True}}],
            [{"manifest_id": "case", "terminal_decision": "ASK_REPEAT", "spoken_allowed": True,
              "failure": "runtime-error", "gate_failures": ["runtime-gate"]}],
        )
        self.assertEqual(len(summary["failures"]), 1)
        failure = summary["failures"][0]
        self.assertEqual(failure["failure"], "runtime-error")
        self.assertEqual(set(failure["gate_failures"]),
                         {"runtime-gate", "unsafe_spoken_output", "unintended_audio_on_reject"})

    def test_output_policy_violations_do_not_require_safety_labels(self) -> None:
        summary = score_rows(
            [{"id": "reject"}, {"id": "repair"}],
            [{"manifest_id": "reject", "terminal_decision": "NO_SPEECH", "spoken_allowed": True},
             {"manifest_id": "repair", "terminal_decision": "REPAIRED_TRANSLATION", "spoken_allowed": True}],
        )
        self.assertEqual(summary["safety"]["samples"], 0)
        self.assertEqual(summary["safety"]["unintended_audio_on_reject"], 1)
        self.assertEqual(summary["safety"]["repair_auto_spoken"], 1)
        self.assertEqual({row["id"] for row in summary["failures"]}, {"reject", "repair"})

    def test_invalid_identity_and_policy_schema_cannot_silently_change_denominators(self) -> None:
        bad_inputs = [
            ([{"id": "same"}, {"id": "same"}], []),
            ([{}], []),
            ([{"id": ""}], []),
            ([{"id": "case"}], [{"id": "case"}, {"id": "case"}]),
            ([{"id": "case"}], [{"id": "unknown"}]),
            ([{"id": "case"}], [{}]),
            ([{"id": "case"}], [{"id": "case", "type": "unknown"}]),
            ([{"id": "case"}], [{"id": "case", "spoken_allowed": "false"}]),
            ([{"id": "case"}], [{"id": "case", "gate_failures": "error"}]),
        ]
        for manifest, results in bad_inputs:
            with self.subTest(manifest=manifest, results=results), self.assertRaises(ValueError):
                score_rows(manifest, results)

    def test_summary_rows_remain_compatible_with_strict_result_join(self) -> None:
        summary = score_rows([{"id": "case"}], [{"manifest_id": "case"}, {"type": "summary", "samples": 1}])
        self.assertEqual(summary["coverage"]["result_samples"], 1)

    def test_cli_invalid_join_exits_two_without_writing_a_success_report(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            manifest, results, output = (directory / name for name in ("manifest.jsonl", "results.jsonl", "output.json"))
            manifest.write_text('{"id":"case"}\n')
            results.write_text('{"id":"unexpected"}\n')
            with patch.object(sys, "argv", ["score.py", "--manifest", str(manifest), "--results", str(results),
                                            "--output", str(output)]), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as caught:
                    main()
            self.assertEqual(caught.exception.code, 2)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
