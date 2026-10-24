#!/usr/bin/env python3
"""Tests for the pre-commit clippy crate selection."""

from __future__ import annotations

import unittest

from scripts.ci.staged_rust_crates import REPO_ROOT, owning_packages


class StagedRustCratesTest(unittest.TestCase):
    roots = [
        (REPO_ROOT / "native/rust/crates/outer", "outer"),
        (REPO_ROOT / "native/rust/crates/outer/nested", "nested"),
    ]
    roots.sort(key=lambda root: len(root[0].parts), reverse=True)

    def test_files_map_to_their_deepest_package_once(self) -> None:
        packages = owning_packages(
            [
                "native/rust/crates/outer/src/lib.rs",
                "native/rust/crates/outer/tests/it.rs",
                "native/rust/crates/outer/nested/src/lib.rs",
            ],
            self.roots,
        )
        self.assertEqual(["nested", "outer"], packages)

    def test_files_outside_every_package_select_nothing(self) -> None:
        self.assertEqual([], owning_packages(["scripts/ci/tool.rs"], self.roots))


if __name__ == "__main__":
    unittest.main()
