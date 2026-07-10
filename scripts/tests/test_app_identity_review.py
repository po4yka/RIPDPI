#!/usr/bin/env python3
"""Unit tests for scripts/ci/check_app_identity_review.py."""
from __future__ import annotations

import copy
import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path


def load_module(module_name: str, relative_path: str):
    root = Path(__file__).resolve().parents[2]
    module_path = root / relative_path
    spec = importlib.util.spec_from_file_location(module_name, module_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


identity = load_module(
    "check_app_identity_review",
    "scripts/ci/check_app_identity_review.py",
)


class AppIdentityReviewTest(unittest.TestCase):
    def setUp(self) -> None:
        self.review = identity.load_json(identity.REVIEW_PATH)
        self.resolved = {
            "schemaVersion": 1,
            "versionName": self.review["reviewedRelease"]["versionName"],
            "versionCode": self.review["reviewedRelease"]["versionCode"],
            "variants": [
                {
                    key: variant[key]
                    for key in ("name", "distribution", "experience", "applicationId")
                }
                for variant in self.review["variants"]
            ],
        }
        self.workflow_text = identity.RELEASE_WORKFLOW_PATH.read_text(encoding="utf-8")

    def validate(
        self,
        review: dict | None = None,
        resolved: dict | None = None,
        workflow: str | None = None,
    ) -> dict:
        return identity.validate_review(
            review if review is not None else self.review,
            resolved if resolved is not None else self.resolved,
            workflow if workflow is not None else self.workflow_text,
        )

    def test_current_review_is_valid(self) -> None:
        summary = self.validate()
        self.assertEqual("0.1.3", summary["versionName"])
        self.assertEqual(11, summary["versionCode"])
        self.assertEqual(6, summary["variantCount"])
        self.assertEqual(3, summary["publishedVariantCount"])
        self.assertEqual([], summary["exactMatches"])
        self.assertEqual(["dpi", "ripdpi"], summary["recognizableTokens"])
        self.assertEqual("elevated", summary["riskLevel"])
        self.assertEqual("retain-stable-id", summary["decision"])

    def test_stale_version_is_rejected(self) -> None:
        resolved = copy.deepcopy(self.resolved)
        resolved["versionCode"] += 1
        with self.assertRaisesRegex(ValueError, "stale review versionCode"):
            self.validate(resolved=resolved)

    def test_new_release_variant_is_rejected(self) -> None:
        resolved = copy.deepcopy(self.resolved)
        resolved["variants"].append(
            {
                "name": "githubStealthRelease",
                "distribution": "github",
                "experience": "stealth",
                "applicationId": "com.example.reader",
            }
        )
        with self.assertRaisesRegex(ValueError, "release variant drift"):
            self.validate(resolved=resolved)

    def test_application_id_drift_is_rejected(self) -> None:
        resolved = copy.deepcopy(self.resolved)
        resolved["variants"][0]["applicationId"] = "com.example.reader"
        with self.assertRaisesRegex(ValueError, "release variant drift for fdroidFullRelease"):
            self.validate(resolved=resolved)

    def test_malformed_source_provenance_is_rejected(self) -> None:
        review = copy.deepcopy(self.review)
        review["sources"][0]["revision"] = "not-a-git-revision"
        with self.assertRaisesRegex(ValueError, "40-character Git SHA-1"):
            self.validate(review=review)

    def test_release_workflow_drift_is_rejected(self) -> None:
        workflow = self.workflow_text.replace(
            "assembleGithubFullRelease",
            "assembleGithubSimpleRelease",
        )
        with self.assertRaisesRegex(ValueError, "release workflow drift"):
            self.validate(workflow=workflow)

    def test_known_catalog_match_requires_explicit_decision(self) -> None:
        review = copy.deepcopy(self.review)
        review["catalog"]["packageIds"].append("com.poyka.ripdpi")
        review["catalog"]["packageIds"].sort()
        review["catalog"]["exactMatches"] = ["com.poyka.ripdpi"]
        review["assessment"]["riskLevel"] = "high"
        with self.assertRaisesRegex(ValueError, "known catalog match requires"):
            self.validate(review=review)

    def test_known_catalog_match_can_be_explicitly_accepted(self) -> None:
        review = copy.deepcopy(self.review)
        review["catalog"]["packageIds"].append("com.poyka.ripdpi")
        review["catalog"]["packageIds"].sort()
        review["catalog"]["exactMatches"] = ["com.poyka.ripdpi"]
        review["assessment"]["riskLevel"] = "high"
        review["decision"].update(
            {
                "action": "accept-known-match",
                "acceptedRisk": "Release maintainers explicitly accept package-list detection for this release.",
            }
        )
        summary = self.validate(review=review)
        self.assertEqual(["com.poyka.ripdpi"], summary["exactMatches"])
        self.assertEqual("accept-known-match", summary["decision"])

    def test_change_id_requires_migration_plan(self) -> None:
        review = copy.deepcopy(self.review)
        review["decision"]["action"] = "change-id"
        with self.assertRaisesRegex(ValueError, "decision.migrationPlan"):
            self.validate(review=review)

    def test_report_surfaces_accepted_recognizability(self) -> None:
        report = identity.render_report(self.validate())
        self.assertIn("Verdict: **PASS**", report)
        self.assertIn("Recognizable identity tokens: `dpi, ripdpi`", report)
        self.assertIn("Risk: `elevated`", report)

    def test_cli_returns_nonzero_for_stale_review(self) -> None:
        resolved = copy.deepcopy(self.resolved)
        resolved["versionName"] = "99.0.0"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            review_path = root / "review.json"
            resolved_path = root / "resolved.json"
            workflow_path = root / "release.yml"
            review_path.write_text(json.dumps(self.review), encoding="utf-8")
            resolved_path.write_text(json.dumps(resolved), encoding="utf-8")
            workflow_path.write_text(self.workflow_text, encoding="utf-8")
            with contextlib.redirect_stderr(io.StringIO()):
                exit_code = identity.main(
                    [
                        "--review",
                        str(review_path),
                        "--resolved",
                        str(resolved_path),
                        "--workflow",
                        str(workflow_path),
                    ]
                )
        self.assertEqual(1, exit_code)

    def test_ci_and_release_workflows_run_the_blocking_gate(self) -> None:
        ci = (identity.ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        release = identity.RELEASE_WORKFLOW_PATH.read_text(encoding="utf-8")
        for workflow in (ci, release):
            self.assertIn("./gradlew :app:writeReleaseIdentityManifest", workflow)
            self.assertIn("python3 scripts/ci/check_app_identity_review.py", workflow)


if __name__ == "__main__":
    unittest.main()
