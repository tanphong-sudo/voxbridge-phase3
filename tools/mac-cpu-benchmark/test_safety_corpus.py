from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from generate_safety_corpus import generate


class SafetyCorpusTest(unittest.TestCase):
    def test_shape_balance_and_group_isolation(self) -> None:
        rows = generate()
        self.assertEqual(len(rows), 120)
        self.assertEqual(sum(row["source_lang"] == "vi" for row in rows), 60)
        self.assertEqual(sum(row["source_lang"] == "en" for row in rows), 60)
        by_group: dict[str, set[str]] = {}
        for row in rows:
            by_group.setdefault(str(row["group_id"]), set()).add(str(row["split"]))
        self.assertTrue(all(len(splits) == 1 for splits in by_group.values()))
        self.assertTrue(all(len([row for row in rows if row["group_id"] == group]) == 2 for group in by_group))

    def test_generated_template_labels_are_provisional(self) -> None:
        rows = generate()
        self.assertTrue(all(row["label_status"] == "provisional-template" for row in rows))
        self.assertTrue(all(row["human_verified"] is False for row in rows))

    def test_safety_rows_have_independent_reference_slots(self) -> None:
        rows = generate()
        safety = [row for row in rows if row["category"] != "lexical-negative"]
        self.assertTrue(safety)
        self.assertTrue(all(row["reference_slots"] for row in safety))
        self.assertTrue(all("ASK_REPEAT" in row["allowed_decisions"] for row in safety))

    def test_lexical_negatives_do_not_carry_safety_slots(self) -> None:
        rows = [row for row in generate() if row["category"] == "lexical-negative"]
        self.assertEqual(len(rows), 20)
        self.assertTrue(all(row["reference_slots"] == {} for row in rows))
        self.assertTrue(all(row["allowed_decisions"] == ["MODEL_TRANSLATION"] for row in rows))

    def test_reference_slots_use_runtime_canonical_ontology(self) -> None:
        rows = generate()
        by_category = {}
        for row in rows:
            by_category.setdefault(row["category"], row["reference_slots"])

        self.assertEqual(by_category["conveyor-id"]["objectType"], "CONVEYOR_BELT")
        self.assertEqual(by_category["hazard-zone"]["objectType"], "HAZARD_ZONE")
        self.assertEqual(by_category["hazard-zone"]["hazard"], "GENERIC_DANGER")
        self.assertEqual(by_category["urgent-gas"]["action"], "STOP")
        self.assertEqual(by_category["urgent-gas"]["objectType"], "MACHINE")


if __name__ == "__main__":
    unittest.main()
