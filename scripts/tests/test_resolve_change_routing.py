from __future__ import annotations

import unittest
from pathlib import Path

from scripts.ci.resolve_change_routing import is_documentation_only


ROOT = Path(__file__).resolve().parents[2]
CI_WORKFLOW = ROOT / ".github/workflows/ci.yml"


class ResolveChangeRoutingTest(unittest.TestCase):
    def test_docs_and_readmes_use_fast_route(self) -> None:
        self.assertTrue(is_documentation_only(["docs/contributor/build-performance.md", "README-ru.md"]))

    def test_any_executable_or_ci_path_uses_full_route(self) -> None:
        self.assertFalse(is_documentation_only(["docs/guide.md", ".github/workflows/ci.yml"]))

    def test_empty_change_set_uses_full_route(self) -> None:
        self.assertFalse(is_documentation_only([]))

    def test_pr_labels_do_not_trigger_the_main_ci_workflow(self) -> None:
        source = CI_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("types: [opened, synchronize, reopened]", source)
        self.assertNotIn("types: [opened, synchronize, reopened, labeled]", source)
        self.assertNotIn("github.event.action == 'labeled'", source)


if __name__ == "__main__":
    unittest.main()
