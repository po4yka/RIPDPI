#!/usr/bin/env python3

from __future__ import annotations

import json
import subprocess
import unittest
from unittest.mock import patch

from scripts.ci import reserve_release_candidate_attempt as attempt


def result(returncode: int, payload: object = None) -> subprocess.CompletedProcess[str]:
    output = "" if payload is None else json.dumps(payload)
    return subprocess.CompletedProcess(["gh"], returncode, output, "")


class ReleaseCandidateAttemptTest(unittest.TestCase):
    @patch.object(attempt, "_gh")
    def test_reserves_durable_ref_and_returns_all_attempts(self, gh) -> None:
        sha = "a" * 40
        ref = "refs/tags/release-candidates/v0.1.5/run-42"
        gh.side_effect = [
            result(1),
            result(0, {}),
            result(0, [[{"ref": ref, "object": {"sha": sha}}]]),
        ]
        self.assertEqual([{"ref": ref, "sha": sha}], attempt.reserve("o/r", "v0.1.5", sha, 42))

    @patch.object(attempt, "_gh")
    def test_existing_ref_with_different_sha_fails_closed(self, gh) -> None:
        gh.return_value = result(0, {
            "ref": "refs/tags/release-candidates/v0.1.5/run-42",
            "object": {"sha": "b" * 40},
        })
        with self.assertRaisesRegex(RuntimeError, "different identity"):
            attempt.reserve("o/r", "v0.1.5", "a" * 40, 42)

    @patch.object(attempt.time, "sleep")
    @patch.object(attempt, "_gh")
    def test_missing_reserved_ref_after_bounded_retry_fails_closed(self, gh, sleep) -> None:
        gh.side_effect = [result(1), result(0, {})] + [result(0, [[]])] * 3
        with self.assertRaisesRegex(RuntimeError, "absent from durable"):
            attempt.reserve("o/r", "v0.1.5", "a" * 40, 42)
        self.assertEqual(2, sleep.call_count)


if __name__ == "__main__":
    unittest.main()
