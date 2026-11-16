from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest


MODULE_PATH = Path(__file__).with_name("evaluate_run.py")
SPEC = importlib.util.spec_from_file_location("evaluate_run", MODULE_PATH)
assert SPEC and SPEC.loader
evaluate_run = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(evaluate_run)


class EvaluateRunTest(unittest.TestCase):
    def test_percentile_interpolates(self) -> None:
        self.assertEqual(evaluate_run.percentile([1.0, 2.0, 3.0, 4.0], 0.5), 2.5)
        self.assertAlmostEqual(evaluate_run.percentile([1.0, 2.0, 3.0, 4.0], 0.95), 3.85)

    def test_required_stages_respect_reject_path(self) -> None:
        reject = {"terminal_decision": "ASK_REPEAT", "timings_ms": {"read_ms": 1, "vad_ms": 2, "total_ms": 3}}
        accepted = {"terminal_decision": "MODEL_TRANSLATION", "timings_ms": {"read_ms": 1, "vad_ms": 2, "total_ms": 3}}
        self.assertEqual(evaluate_run.required_stage_failures(reject), [])
        self.assertEqual(
            evaluate_run.required_stage_failures(accepted),
            ["missing_stage:asr_ms", "missing_stage:decision_ms", "missing_stage:mt_ms"],
        )

    def test_rss_trend_uses_completed_turns(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "rss.csv"
            path.write_text(
                "elapsed_ms,samples_completed,rss_kib,pids\n"
                "0,0,102400,1\n"
                "100,10,112640,1\n"
                "200,20,122880,1\n",
                encoding="utf-8",
            )
            summary = evaluate_run.rss_summary(path, warmup_samples=10)
            assert summary is not None
            self.assertEqual(summary["max_samples_completed"], 20)
            self.assertAlmostEqual(summary["warm_rss_slope_mib_per_100_turns"], 100.0)


if __name__ == "__main__":
    unittest.main()
