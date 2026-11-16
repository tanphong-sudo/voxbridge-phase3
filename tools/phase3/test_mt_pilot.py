import unittest
from mt_pilot import prepare_dataset, prompt_for, strip_envit5_prefix, summarize, stop_reason, generation_options, checkpoint_files, score_corpus


class MtPilotTest(unittest.TestCase):
    def test_balanced_split_preserves_missing_original_reference(self):
        rows = [{"id": f"{lang}-{i}", "source_lang": lang,
                 "target_lang": "en" if lang == "vi" else "vi",
                 "reference_source": f"Source {i}{'?' if i % 3 else '.'}",
                 "reference_target": None if lang == "vi" else "Đáp án",
                 "translation_source_label": None if lang == "vi" else "AI"}
                for lang in ("vi", "en") for i in range(100)]
        refs = [{"id": r["id"], "source_text_original": r["reference_source"],
                 "target_reference_draft": "Draft", "human_verified": False} for r in rows]
        out = prepare_dataset(rows, refs)
        for lang in ("vi", "en"):
            self.assertEqual(50, sum(r["source_lang"] == lang and r["split"] == "dev" for r in out))
        self.assertIsNone(out[0]["reference_target_original"])
        self.assertEqual(out, prepare_dataset(rows, refs))

    def test_rejects_duplicate_or_mismatched_reference(self):
        r = {"id": "x", "source_lang": "vi", "target_lang": "en", "reference_source": "Xin chào"}
        with self.assertRaisesRegex(ValueError, "duplicate"):
            prepare_dataset([r, r], [])
        with self.assertRaisesRegex(ValueError, "source mismatch"):
            prepare_dataset([r], [{"id": "x", "source_text_original": "Other", "target_reference_draft": "Hi"}])

    def test_prompt_does_not_contain_reference(self):
        self.assertEqual("vi: Xin chào", prompt_for("envit5", "Xin chào", "vi", "en"))
        self.assertIn("Vietnamese into English", prompt_for("qwen3", "Xin chào", "vi", "en"))
        self.assertEqual("Xin chào", prompt_for("opus", "Xin chào", "vi", "en"))
        self.assertEqual("Envoy arrived", strip_envit5_prefix("Envoy arrived"))
        self.assertEqual("Hello", strip_envit5_prefix("EN : Hello"))

    def test_missing_failed_and_unreferenced_outputs_are_not_hidden(self):
        manifest = [{"id": "a", "reference_target": "yes"}, {"id": "b", "reference_target": "no"},
                    {"id": "c", "reference_target": None}]
        rows = [{"id": "a", "hypothesis": "yes", "latency_ms": 10, "failure": None},
                {"id": "b", "hypothesis": "", "latency_ms": 20, "failure": "error"}]
        s = summarize(rows, manifest)
        self.assertEqual((3, 2, 1, 1, 1, 2), tuple(s[k] for k in
                         ("n_expected", "n_records", "n_missing", "n_failed", "n_output", "n_reference")))
        self.assertEqual(["c"], s["missing_ids"])

    def test_unknown_duplicate_result_ids_fail(self):
        with self.assertRaises(ValueError):
            summarize([{"id": "z"}], [{"id": "a"}])
        with self.assertRaises(ValueError):
            summarize([{"id": "a"}, {"id": "a"}], [{"id": "a"}])

    def test_forced_eos_at_cap_does_not_hide_truncation(self):
        self.assertEqual("token_cap", stop_reason([7, 8, 0], [0], 3))
        self.assertEqual("eos", stop_reason([7, 0], [0], 3))
        self.assertEqual("token_cap", stop_reason([7, 8, 9], [0], 3))

    def test_vinai_uses_target_decoder_start_and_no_forced_bos(self):
        class Tokenizer:
            lang_code_to_id = {"en_XX": 11, "vi_VN": 22}
        opts = generation_options("vinai", Tokenizer(), "en", 5)
        self.assertEqual(11, opts["decoder_start_token_id"])
        self.assertIsNone(opts["forced_bos_token_id"])
        self.assertTrue(opts["early_stopping"])
        self.assertEqual({}, generation_options("envit5", Tokenizer(), "en", 1))
        self.assertNotIn("early_stopping", generation_options("vinai", Tokenizer(), "vi", 1))

    def test_empty_reference_group_is_not_sent_to_metric(self):
        class Metric:
            def corpus_score(self, hyps, refs):
                raise AssertionError("Empty group must not be scored")
        self.assertIsNone(score_corpus(Metric(), [], []))

    def test_checkpoint_download_plan_does_not_request_two_weight_formats(self):
        self.assertEqual(["config.json", "pytorch_model.bin"], checkpoint_files("vinai"))
        self.assertEqual(["config.json", "pytorch_model.bin"], checkpoint_files("envit5"))
        self.assertEqual(["config.json", "model.safetensors"], checkpoint_files("small100"))


if __name__ == "__main__":
    unittest.main()
