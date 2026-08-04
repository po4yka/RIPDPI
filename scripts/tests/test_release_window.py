#!/usr/bin/env python3

from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from datetime import UTC, datetime
from pathlib import Path

from scripts.ci.check_release_window import WindowError, evaluate_release_window


ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "quality/release-gates/release-contract.json"


class ReleaseWindowTest(unittest.TestCase):
    def make_repo(self, root: Path) -> tuple[Path, str]:
        repo = root / "repo"
        repo.mkdir()
        subprocess.run(["git", "init", "-q", "-b", "main"], cwd=repo, check=True)
        subprocess.run(["git", "config", "user.name", "Test"], cwd=repo, check=True)
        subprocess.run(["git", "config", "user.email", "test@example.invalid"], cwd=repo, check=True)
        (repo / "state.txt").write_text("base\n", encoding="utf-8")
        subprocess.run(["git", "add", "state.txt"], cwd=repo, check=True)
        subprocess.run(["git", "commit", "-q", "-m", "chore(release): cut window"], cwd=repo, check=True)
        start = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
        return repo, start

    def commit(self, repo: Path, subject: str) -> str:
        path = repo / "state.txt"
        path.write_text(path.read_text(encoding="utf-8") + subject + "\n", encoding="utf-8")
        subprocess.run(["git", "add", "state.txt"], cwd=repo, check=True)
        subprocess.run(["git", "commit", "-q", "-m", subject], cwd=repo, check=True)
        return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()

    def test_release_fix_window_accepts_bounded_non_feature_commits(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            repo, start = self.make_repo(Path(raw))
            candidate = self.commit(repo, "fix(release): correct packaged metadata")
            report = evaluate_release_window(
                repo=repo,
                contract_path=CONTRACT,
                release_tag="v0.1.5",
                start_sha=candidate,
                candidate_sha=candidate,
                started_at=datetime(2026, 8, 3, tzinfo=UTC),
                now=datetime(2026, 8, 4, tzinfo=UTC),
                runs=[{
                    "displayTitle": "Android release candidate v0.1.5 @ abc",
                    "headSha": candidate,
                    "createdAt": "2026-08-03T12:00:00Z",
                }],
            )
            self.assertEqual(0, report["commitCount"])
            self.assertEqual(1, report["candidateRunCount"])

    def test_window_rejects_features_expiry_commit_overflow_and_candidate_churn(self) -> None:
        cases = ("feat(ui): add late feature", "refactor: change release architecture")
        for subject in cases:
            with self.subTest(subject=subject), tempfile.TemporaryDirectory() as raw:
                repo, start = self.make_repo(Path(raw))
                candidate = self.commit(repo, subject)
                with self.assertRaisesRegex(WindowError, "not release-window safe"):
                    evaluate_release_window(
                        repo, CONTRACT, "v0.1.5", start, candidate,
                        datetime(2026, 8, 3, tzinfo=UTC), datetime(2026, 8, 4, tzinfo=UTC), []
                    )

        with tempfile.TemporaryDirectory() as raw:
            repo, start = self.make_repo(Path(raw))
            candidate = self.commit(repo, "fix: release correction")
            with self.assertRaisesRegex(WindowError, "expired"):
                evaluate_release_window(
                    repo, CONTRACT, "v0.1.5", start, candidate,
                    datetime(2026, 7, 1, tzinfo=UTC), datetime(2026, 8, 4, tzinfo=UTC), []
                )

    def test_window_rejects_naive_timestamps_and_recut_after_first_candidate(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            repo, start = self.make_repo(Path(raw))
            moved_cut = self.commit(repo, "fix: pre-candidate correction")
            first = self.commit(repo, "fix: first candidate")
            later = self.commit(repo, "fix: later candidate")
            runs = [{
                "displayTitle": "Android release candidate v0.1.5 @ first",
                "headSha": first,
                "createdAt": "2026-08-03T12:00:00Z",
            }]
            with self.assertRaisesRegex(WindowError, "start must equal the first candidate SHA"):
                evaluate_release_window(
                    repo, CONTRACT, "v0.1.5", moved_cut, later,
                    datetime(2026, 8, 3, 11, tzinfo=UTC),
                    datetime(2026, 8, 4, tzinfo=UTC), runs,
                )
            report = evaluate_release_window(
                repo, CONTRACT, "v0.1.5", first, later,
                datetime(2026, 1, 1, tzinfo=UTC),
                datetime(2026, 8, 4, tzinfo=UTC), runs,
            )
            self.assertEqual("2026-08-03T12:00:00Z", report["startedAt"])
            with self.assertRaisesRegex(WindowError, "timestamp must include a timezone"):
                evaluate_release_window(
                    repo, CONTRACT, "v0.1.5", start, first,
                    datetime(2026, 8, 3), datetime(2026, 8, 4, tzinfo=UTC), [],
                )
            noisy_runs = [
                {"displayTitle": f"Android release candidate v0.1.5 @ {index}"}
                for index in range(6)
            ]
            with self.assertRaisesRegex(WindowError, "candidate run limit"):
                evaluate_release_window(
                    repo, CONTRACT, "v0.1.5", start, first,
                    datetime(2026, 8, 3, tzinfo=UTC), datetime(2026, 8, 4, tzinfo=UTC), noisy_runs
                )

    def test_candidate_workflow_enforces_machine_readable_window_before_signing(self) -> None:
        source = (ROOT / ".github/workflows/release-candidate.yml").read_text(encoding="utf-8")
        contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
        self.assertEqual(72, contract["releaseWindow"]["maxAgeHours"])
        self.assertEqual(20, contract["releaseWindow"]["maxCommits"])
        self.assertEqual(5, contract["releaseWindow"]["maxCandidateRuns"])
        self.assertIn("RIPDPI_RELEASE_WINDOW_START_SHA", source)
        self.assertIn("RIPDPI_RELEASE_WINDOW_STARTED_AT", source)
        self.assertIn("scripts/ci/check_release_window.py", source)
        self.assertIn("gh api --paginate --slurp", source)
        self.assertLess(
            source.index("scripts/ci/check_release_window.py"),
            source.index("environment: release-signing"),
        )


if __name__ == "__main__":
    unittest.main()
