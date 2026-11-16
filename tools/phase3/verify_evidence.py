#!/usr/bin/env python3
"""Read-only ledger/file consistency checks, never a runtime or official score certification."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
from typing import Any


SCOPES = {"administrative", "source_inspection", "mac_cpu_historical", "emulator_fake_ui", "qdc_file",
          "physical_live_offline", "package_inspection", "human_ratings", "video", "installation", "protocol"}
HISTORICAL = {"mac_cpu_historical", "emulator_fake_ui"}
HISTORY_FIELDS = {"P3-T014-R08", "P3-P042-1"}
ROLE_SCOPES = {"apk": {"package_inspection"}, "model_manifest": {"package_inspection"},
               "demo_video": {"video"}, "benchmark_log": {"qdc_file", "physical_live_offline"},
               "quality_ratings": {"human_ratings"}, "installation_log": {"installation"}}
HASH = re.compile(r"[0-9a-f]{64}")


def _object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_record(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_object)
    if not isinstance(value, dict):
        raise ValueError(f"{path}: JSON root must be an object")
    return value


def _text(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _member(value: Any, allowed: set[str]) -> bool:
    return isinstance(value, str) and value in allowed


def _digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _scope_requirements(field: dict) -> list[set[str]]:
    """Each set is an alternative; at least one scope from every set must be cited."""
    key, section = field["id"], field["section"]
    if section == "2. Demo video": return [{"video"}]
    if section == "3.2 Architecture of the demonstrated solution": return [{"source_inspection"}]
    if section == "3.3 Minimum requirements to run this build": return [{"qdc_file", "physical_live_offline"}]
    if section == "3.4 Install and run offline": return [{"installation"}]
    if key == "P3-T041-R01": return [{"human_ratings"}, {"qdc_file", "physical_live_offline"}]
    if key in {"P3-T041-R02", "P3-T041-R04"}: return [{"qdc_file", "physical_live_offline"}]
    if key == "P3-T041-R03": return [{"package_inspection"}]
    if key == "P3-T041-R05": return [{"physical_live_offline"}, {"video"}]
    if key == "P3-T014-R07": return [{"physical_live_offline"}]
    return []


def check_record(record: dict, inventory: dict, evidence_root: Path, mode: str) -> dict:
    if mode not in {"planning", "submission"}:
        raise ValueError("unsupported mode")
    problems: list[str] = []
    issues = problems.append
    if record.get("schema_version") != 1:
        issues("schema_version must be 1")
    release = record.get("release")
    if not isinstance(release, dict):
        issues("release must be an object")
        release = {}
    if mode == "submission":
        for key in ("release_id", "version_name", "device_id"):
            if not _text(release.get(key)): issues(f"release.{key} missing")
        version = release.get("version_code")
        if not isinstance(version, int) or isinstance(version, bool) or version < 1:
            issues("release.version_code must be a positive integer")
        commit = release.get("source_commit")
        if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
            issues("release.source_commit requires full lowercase SHA-1")
        for key in ("apk_sha256", "model_manifest_sha256"):
            if not isinstance(release.get(key), str) or not HASH.fullmatch(release[key]):
                issues(f"release.{key} missing or invalid")
        if not _text(record.get("registered_team_name")): issues("registered_team_name missing")
    if not _text(record.get("project_name")): issues("project_name missing")
    decisions = record.get("unresolved_decisions")
    if not isinstance(decisions, list) or not all(_text(value) for value in decisions):
        issues("unresolved_decisions must be a list of nonempty strings")
    elif mode == "submission" and decisions:
        issues("unresolved decisions remain")

    expected: dict[str, dict] = {}
    items = inventory.get("items")
    if not isinstance(items, list): raise ValueError("inventory.items must be a list")
    for item in items:
        if not isinstance(item, dict) or not _text(item.get("id")) or not _text(item.get("section")):
            raise ValueError("inventory contains invalid field")
        if item["id"] in expected: raise ValueError("inventory contains duplicate ID")
        expected[item["id"]] = item
    if not expected: raise ValueError("inventory has no fields")
    root = evidence_root.resolve()
    if not root.is_dir(): issues("evidence root is not a directory")
    artifacts = record.get("artifacts")
    if not isinstance(artifacts, list):
        issues("artifacts must be a list")
        artifacts = []
    by_id: dict[str, dict] = {}
    for artifact in artifacts:
        if not isinstance(artifact, dict) or not _text(artifact.get("id")):
            issues("artifact requires nonempty id")
            continue
        aid = artifact["id"]
        if aid in by_id:
            issues(f"duplicate artifact id: {aid}")
            continue
        by_id[aid] = artifact
        for key in ("role", "release_id"):
            if not _text(artifact.get(key)): issues(f"{aid}: {key} missing")
        scope = artifact.get("scope")
        if not _member(scope, SCOPES): issues(f"{aid}: unknown scope")
        status = artifact.get("verification_status")
        if not _member(status, {"checked", "unreviewed"}): issues(f"{aid}: invalid verification status")
        if status == "checked" and not all(_text(artifact.get(key)) for key in ("reviewer", "method")):
            issues(f"{aid}: checked artifact requires reviewer and method")
        if mode == "submission":
            if status != "checked": issues(f"{aid}: artifact not reviewed")
            if not _member(scope, HISTORICAL) and artifact.get("release_id") != release.get("release_id"):
                issues(f"{aid}: artifact release mismatch")
        size = artifact.get("bytes")
        if not isinstance(size, int) or isinstance(size, bool) or size < 0:
            issues(f"{aid}: bytes must be a nonnegative integer")
        elif mode == "submission" and size == 0:
            issues(f"{aid}: empty file cannot support final evidence")
        digest = artifact.get("sha256")
        if not isinstance(digest, str) or not HASH.fullmatch(digest): issues(f"{aid}: invalid SHA-256")
        relative = artifact.get("relative_path")
        if not _text(relative):
            issues(f"{aid}: missing relative_path")
            continue
        path = Path(relative)
        if path.is_absolute() or ".." in path.parts:
            issues(f"{aid}: absolute or parent path rejected")
            continue
        try:
            resolved = (root / path).resolve(strict=True)
            if not resolved.is_relative_to(root):
                issues(f"{aid}: path escapes evidence root")
                continue
            if not resolved.is_file():
                issues(f"{aid}: artifact is not a regular file")
                continue
            if resolved.stat().st_size != size: issues(f"{aid}: file size mismatch")
            if _digest(resolved) != digest: issues(f"{aid}: file hash mismatch")
        except (OSError, RuntimeError) as error:
            issues(f"{aid}: file unavailable ({type(error).__name__})")

    fields = record.get("fields")
    if not isinstance(fields, list):
        issues("fields must be a list")
        fields = []
    seen: set[str] = set()
    for field in fields:
        if not isinstance(field, dict) or not _text(field.get("id")):
            issues("field requires nonempty id")
            continue
        key = field["id"]
        if key in seen: issues(f"duplicate field id: {key}")
        seen.add(key)
        if key not in expected:
            issues(f"unknown field id: {key}")
            continue
        if field.get("section") != expected[key]["section"]: issues(f"{key}: section mismatch")
        if not _member(field.get("plan_status"), {"pending", "written"}): issues(f"{key}: invalid plan status")
        status = field.get("evidence_status")
        if not _member(status, {"missing", "collected", "reviewed"}): issues(f"{key}: invalid evidence status")
        references = field.get("artifact_ids")
        if not isinstance(references, list) or not all(_text(ref) for ref in references):
            issues(f"{key}: artifact_ids must be a list of nonempty strings")
            references = []
        if len(set(references)) != len(references): issues(f"{key}: duplicate artifact reference")
        if status == "missing" and (field.get("claim") is not None or references):
            issues(f"{key}: missing evidence must not carry a claim or references")
        if _member(status, {"collected", "reviewed"}) and (not _text(field.get("claim")) or not references):
            issues(f"{key}: claim and references required")
        scopes: set[str] = set()
        for ref in references:
            artifact = by_id.get(ref)
            if artifact is None:
                issues(f"{key}: unknown artifact reference {ref}")
                continue
            scope = artifact.get("scope")
            if isinstance(scope, str): scopes.add(scope)
            if mode == "submission" and key not in HISTORY_FIELDS and _member(scope, HISTORICAL):
                issues(f"{key}: historical/fake evidence cannot support this final field")
        if mode == "submission":
            if field.get("plan_status") != "written" or status != "reviewed":
                issues(f"{key}: field not reviewed")
            for alternatives in _scope_requirements(expected[key]):
                if not (scopes & alternatives): issues(f"{key}: required evidence scope missing")
    for missing in sorted(set(expected) - seen): issues(f"missing field id: {missing}")
    if mode == "submission":
        for role, allowed in ROLE_SCOPES.items():
            entries = [item for item in artifacts if isinstance(item, dict) and item.get("role") == role]
            if not entries: issues(f"essential artifact role missing: {role}")
            for artifact in entries:
                if not _member(artifact.get("scope"), allowed): issues(f"{role}: invalid scope")
                if role in {"apk", "model_manifest"} and artifact.get("sha256") != release.get(role + "_sha256"):
                    issues(f"{role}: release hash mismatch")
    return {"mode": mode, "metadata_consistent": not problems,
            "submission_metadata_complete": mode == "submission" and not problems,
            "runtime_verified": False, "expected_fields": len(expected), "issues": problems,
            "note": "File/ledger consistency only; human review and actual runtime acceptance remain required."}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--record", required=True, type=Path)
    parser.add_argument("--inventory", type=Path, default=Path(__file__).resolve().parents[2] /
                        "submission/phase-3/templates/template-field-inventory.json")
    parser.add_argument("--evidence-root", required=True, type=Path)
    parser.add_argument("--mode", choices=("planning", "submission"), default="submission")
    args = parser.parse_args(argv)
    try:
        report = check_record(load_record(args.record), load_record(args.inventory), args.evidence_root, args.mode)
    except (ValueError, OSError) as error:
        parser.error(str(error))
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["metadata_consistent"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
