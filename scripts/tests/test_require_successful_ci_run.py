#!/usr/bin/env python3
"""Tests for exact-SHA CI provenance selection."""

from __future__ import annotations

import unittest
from unittest.mock import patch

from scripts.ci.require_successful_ci_run import (
    ProvenanceError,
    require_successful_ci_run,
)


SHA = "ab" * 20


def run_payload(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "conclusion": "success",
        "event": "push",
        "head_branch": "main",
        "head_sha": SHA,
        "id": 123,
        "path": ".github/workflows/ci.yml",
        "run_attempt": 1,
    }
    payload.update(overrides)
    return payload


class RequireSuccessfulCiRunTest(unittest.TestCase):
    @patch("scripts.ci.require_successful_ci_run.fetch_json")
    def test_requires_exact_workflow_run_and_successful_aggregate(self, fetch: object) -> None:
        fetch.side_effect = [
            {"workflow_runs": [run_payload()]},
            {"jobs": [{"name": "ci-required", "conclusion": "success"}]},
        ]

        result = require_successful_ci_run(
            repository="po4yka/RIPDPI",
            sha=SHA,
            token="token",
        )

        self.assertEqual(123, result["runId"])
        self.assertEqual(SHA, result["headSha"])
        self.assertEqual("ci-required", result["aggregateJob"])

    @patch("scripts.ci.require_successful_ci_run.fetch_json")
    def test_rejects_success_from_another_sha_or_workflow(self, fetch: object) -> None:
        fetch.return_value = {
            "workflow_runs": [
                run_payload(head_sha="cd" * 20),
                run_payload(path=".github/workflows/other.yml"),
            ]
        }

        with self.assertRaisesRegex(ProvenanceError, "no successful"):
            require_successful_ci_run(
                repository="po4yka/RIPDPI",
                sha=SHA,
                token="token",
            )

    @patch("scripts.ci.require_successful_ci_run.fetch_json")
    def test_rejects_missing_or_failed_aggregate(self, fetch: object) -> None:
        for jobs, expected in (
            ([{"name": "ci-required", "conclusion": "failure"}], "not success"),
            ([], "exactly one"),
        ):
            with self.subTest(jobs=jobs):
                fetch.side_effect = [
                    {"workflow_runs": [run_payload()]},
                    {"jobs": jobs},
                ]
                with self.assertRaisesRegex(ProvenanceError, expected):
                    require_successful_ci_run(
                        repository="po4yka/RIPDPI",
                        sha=SHA,
                        token="token",
                    )

    def test_rejects_invalid_identity_inputs_without_api_call(self) -> None:
        for repository, sha, token in (
            ("not-a-repository", SHA, "token"),
            ("po4yka/RIPDPI", "short", "token"),
            ("po4yka/RIPDPI", SHA, ""),
        ):
            with self.subTest(repository=repository, sha=sha, token=bool(token)):
                with self.assertRaises(ProvenanceError):
                    require_successful_ci_run(
                        repository=repository,
                        sha=sha,
                        token=token,
                    )


if __name__ == "__main__":
    unittest.main()
