"""Regression contracts for fast, path-filtered pull-request workflows."""

from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def pull_request_paths(workflow_name: str) -> set[str]:
    source = (ROOT / ".github/workflows" / workflow_name).read_text(encoding="utf-8")
    match = re.search(
        r"(?ms)^  pull_request:\n    paths:\n(?P<paths>(?:      - [^\n]+\n)+)",
        source,
    )
    if match is None:
        raise AssertionError(
            f"{workflow_name} has no path-filtered pull_request trigger"
        )
    return set(re.findall(r'(?m)^      - "([^"]+)"$', match.group("paths")))


class PathFilteredPullRequestChecksTest(unittest.TestCase):
    def test_play_store_screenshots_run_for_generator_or_baseline_changes(self) -> None:
        self.assertEqual(
            {
                "play-store-screenshots/**",
                "docs/screenshots/**",
                ".github/workflows/play-store-screenshots.yml",
            },
            pull_request_paths("play-store-screenshots.yml"),
        )

    def test_fixture_check_remains_scoped_to_its_inputs(self) -> None:
        paths = pull_request_paths("fleet-fixtures.yml")
        self.assertTrue(
            {
                "core/data/src/test/resources/fleet-fixtures/**",
                "scripts/refresh-fleet-fixtures.sh",
                "scripts/fleet-fixtures/**",
                "scripts/ci/check_fleet_fixtures.py",
                "scripts/tests/test_fleet_fixtures.py",
                ".github/workflows/fleet-fixtures.yml",
            }.issubset(paths)
        )

    def test_upstream_spec_watch_runs_for_spec_or_fixture_contract_changes(
        self,
    ) -> None:
        self.assertEqual(
            {
                "native/rust/crates/**/SPEC.md",
                "native/rust/crates/**/SPEC_VERSION.md",
                "contract-fixtures/**",
                "scripts/ci/check_protocol_fixture_versions.py",
                "scripts/ci/verify_spec_md_present.sh",
                "scripts/ci/verify_spec_versions.py",
                "scripts/tests/test_protocol_fixture_versions.py",
                ".github/workflows/upstream-spec-watch.yml",
            },
            pull_request_paths("upstream-spec-watch.yml"),
        )


if __name__ == "__main__":
    unittest.main()
