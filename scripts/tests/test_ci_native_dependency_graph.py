from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CI_WORKFLOW = ROOT / ".github/workflows/ci.yml"


def job_source(name: str) -> str:
    source = CI_WORKFLOW.read_text(encoding="utf-8")
    match = re.search(rf"(?ms)^  {re.escape(name)}:\n.*?(?=^  [\w-]+:\n|\Z)", source)
    if match is None:
        raise AssertionError(f"missing {name} CI job")
    return match.group(0)


class NativeDependencyGraphTest(unittest.TestCase):
    def test_instrumented_tests_wait_only_for_x86_64_shard(self) -> None:
        self.assertIn(
            "needs: [change-routing, rust-native-x86_64]",
            job_source("android-instrumented-tests"),
        )

    def test_packaging_consumers_wait_for_complete_abi_set(self) -> None:
        expected_needs = "needs: [change-routing, rust-native-packaging, rust-native-x86_64]"
        self.assertIn(expected_needs, job_source("build-android-debug"))
        self.assertIn(expected_needs, job_source("release-verification"))

    def test_packaging_matrix_excludes_x86_64(self) -> None:
        packaging = job_source("rust-native-packaging")
        self.assertNotIn("x86_64-linux-android", packaging)
        self.assertIn("rust-native-x86_64", CI_WORKFLOW.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
