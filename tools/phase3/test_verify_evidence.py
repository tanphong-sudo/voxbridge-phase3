from __future__ import annotations

import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_evidence import check_record, load_record, main


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.inventory = json.loads((Path(__file__).resolve().parents[2] /
                                     "submission/phase-3/templates/template-field-inventory.json").read_text())
        self.record = json.loads((Path(__file__).resolve().parents[2] /
                                  "submission/phase-3/templates/submission-record.example.json").read_text())

    def complete_fixture(self):
        """Synthetic file metadata only; never evidence of a real successful prototype."""
        self.record["registered_team_name"] = "Synthetic test team"
        self.record["unresolved_decisions"] = []
        self.record["release"] = dict(release_id="fixture", version_name="fixture", version_code=1,
                                      source_commit="a" * 40, device_id="fixture-device",
                                      apk_sha256=None, model_manifest_sha256=None)
        roles = {"apk": "package_inspection", "model_manifest": "package_inspection", "demo_video": "video",
                 "benchmark_log": "physical_live_offline", "quality_ratings": "human_ratings",
                 "installation_log": "installation", "source_record": "source_inspection",
                 "team_record": "administrative", "protocol": "protocol"}
        for role, scope in roles.items():
            path = self.root / (role + ".fixture")
            path.write_bytes(b"synthetic test fixture only")
            sha = hashlib.sha256(path.read_bytes()).hexdigest()
            self.record["artifacts"].append(dict(id=role, relative_path=path.name, sha256=sha,
                                                  bytes=path.stat().st_size, release_id="fixture", role=role,
                                                  scope=scope, verification_status="checked", reviewer="test",
                                                  method="synthetic unit-test metadata, not actual runtime"))
            if role in {"apk", "model_manifest"}:
                self.record["release"][role + "_sha256"] = sha
        for field in self.record["fields"]:
            field.update(evidence_status="reviewed", claim="Synthetic fixture, not a real claim",
                         artifact_ids=list(roles))
        return self.record

    def report(self, mode="submission"):
        return check_record(self.record, self.inventory, self.root, mode)

    def test_planning_example_is_valid_metadata_but_not_complete_submission(self):
        planning = self.report("planning")
        self.assertTrue(planning["metadata_consistent"])
        self.assertFalse(planning["submission_metadata_complete"])
        self.assertFalse(self.report()["metadata_consistent"])

    def test_complete_synthetic_metadata_does_not_claim_runtime_verified(self):
        self.complete_fixture()
        self.assertTrue(self.report()["metadata_consistent"], self.report())
        self.assertFalse(self.report()["runtime_verified"])

    def test_missing_duplicate_unknown_and_wrong_section_fields_fail(self):
        original = copy.deepcopy(self.record)
        for mutation in ("missing", "duplicate", "unknown", "section"):
            self.record = copy.deepcopy(original)
            if mutation == "missing": self.record["fields"].pop()
            elif mutation == "duplicate": self.record["fields"].append(self.record["fields"][0])
            elif mutation == "unknown": self.record["fields"][0]["id"] = "invented"
            else: self.record["fields"][0]["section"] = "wrong section"
            with self.subTest(mutation=mutation): self.assertFalse(self.report("planning")["metadata_consistent"])

    def test_reviewed_claim_requires_evidence_and_known_reference(self):
        field = self.record["fields"][0]
        field.update(evidence_status="reviewed", claim="claim", artifact_ids=[])
        self.assertFalse(self.report("planning")["metadata_consistent"])
        field["artifact_ids"] = ["missing-file"]
        self.assertFalse(self.report("planning")["metadata_consistent"])

    def test_hash_bytes_and_release_mismatch_are_rejected(self):
        original = copy.deepcopy(self.complete_fixture())
        for key, value in (("sha256", "0" * 64), ("bytes", 999), ("release_id", "other")):
            self.record = copy.deepcopy(original)
            self.record["artifacts"][0][key] = value
            with self.subTest(key=key): self.assertFalse(self.report()["metadata_consistent"])

    def test_missing_file_and_missing_essential_role_are_rejected(self):
        self.complete_fixture()
        self.record["artifacts"][0]["relative_path"] = "absent.bin"
        self.assertFalse(self.report()["metadata_consistent"])
        self.record["artifacts"][0]["relative_path"] = "apk.fixture"
        self.record["artifacts"][0]["role"] = "other"
        self.assertFalse(self.report()["metadata_consistent"])

    def test_unresolved_decisions_and_unreviewed_artifacts_block_submission(self):
        self.complete_fixture()
        self.record["unresolved_decisions"] = ["device"]
        self.assertFalse(self.report()["metadata_consistent"])
        self.record["unresolved_decisions"] = []
        self.record["artifacts"][0]["verification_status"] = "unreviewed"
        self.assertFalse(self.report()["metadata_consistent"])

    def test_historical_or_fake_evidence_cannot_support_final_metrics(self):
        original = copy.deepcopy(self.complete_fixture())
        for scope in ("mac_cpu_historical", "emulator_fake_ui"):
            self.record = copy.deepcopy(original)
            for artifact in self.record["artifacts"]: artifact["scope"] = scope
            with self.subTest(scope=scope): self.assertFalse(self.report()["metadata_consistent"])

    def test_video_field_cannot_cite_only_administrative_record(self):
        self.complete_fixture()
        field = next(x for x in self.record["fields"] if x["id"] == "P3-P016-1")
        field["artifact_ids"] = ["team_record"]
        self.assertFalse(self.report()["metadata_consistent"])

    def test_duplicate_artifact_identity_is_rejected(self):
        self.complete_fixture()
        self.record["artifacts"].append(copy.deepcopy(self.record["artifacts"][0]))
        self.assertFalse(self.report()["metadata_consistent"])

    def test_non_string_status_and_scope_are_reported_without_crashing(self):
        original = copy.deepcopy(self.complete_fixture())
        for target, key in (("artifacts", "scope"), ("artifacts", "verification_status"),
                            ("fields", "plan_status"), ("fields", "evidence_status")):
            for value in ([], {}):
                self.record = copy.deepcopy(original)
                self.record[target][0][key] = value
                with self.subTest(target=target, key=key, value=value):
                    self.assertFalse(self.report()["metadata_consistent"])

    def test_invalid_inventory_items_raise_clear_value_error(self):
        for value in (None, {}, "invalid"):
            self.inventory["items"] = value
            with self.subTest(value=value), self.assertRaises(ValueError): self.report()

    def test_absolute_parent_and_symlink_escape_paths_are_rejected(self):
        self.complete_fixture()
        outside = self.root.parent / (self.root.name + "-outside.fixture")
        outside.write_bytes(b"outside fixture")
        self.addCleanup(outside.unlink)
        link = self.root / "escape.fixture"
        link.symlink_to(outside)
        for name in (str(outside), "../" + outside.name, link.name):
            self.record["artifacts"][0]["relative_path"] = name
            with self.subTest(name=name): self.assertFalse(self.report()["metadata_consistent"])

    def test_duplicate_json_keys_and_non_object_roots_are_rejected(self):
        p = self.root / "bad.json"
        for contents in ('{"release":1,"release":2}', '[]'):
            p.write_text(contents)
            with self.subTest(contents=contents), self.assertRaises(ValueError): load_record(p)

    def test_cli_defaults_to_incomplete_submission_and_planning_is_explicit(self):
        record = self.root / "record.json"
        record.write_text(json.dumps(self.record))
        inventory = self.root / "inventory.json"
        inventory.write_text(json.dumps(self.inventory))
        args = ["--record", str(record), "--inventory", str(inventory), "--evidence-root", str(self.root)]
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(main(args), 1)
            self.assertEqual(main(args + ["--mode", "planning"]), 0)


if __name__ == "__main__":
    unittest.main()
