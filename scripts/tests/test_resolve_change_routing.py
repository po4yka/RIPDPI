from __future__ import annotations

import unittest

from scripts.ci.resolve_change_routing import is_documentation_only


class ResolveChangeRoutingTest(unittest.TestCase):
    def test_docs_and_readmes_use_fast_route(self) -> None:
        self.assertTrue(is_documentation_only(["docs/contributor/build-performance.md", "README-ru.md"], force_full=False))

    def test_any_executable_or_ci_path_uses_full_route(self) -> None:
        self.assertFalse(is_documentation_only(["docs/guide.md", ".github/workflows/ci.yml"], force_full=False))

    def test_label_retrigger_forces_full_route(self) -> None:
        self.assertFalse(is_documentation_only(["docs/guide.md"], force_full=True))

    def test_empty_change_set_uses_full_route(self) -> None:
        self.assertFalse(is_documentation_only([], force_full=False))


if __name__ == "__main__":
    unittest.main()
