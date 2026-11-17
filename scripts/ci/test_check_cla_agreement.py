import importlib.util
import os
import unittest
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).with_name("check_cla_agreement.py")
SPEC = importlib.util.spec_from_file_location("check_cla_agreement", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

# Copied from CLA.md "How to agree" so a drift between the two fails here.
STATEMENT = "I have read the RIPDPI Contributor License Agreement (CLA.md) and I agree to its terms for this contribution"


def run(author: str, body: str | None) -> int:
    env = {"PR_AUTHOR": author}
    if body is not None:
        env["PR_BODY"] = body
    with mock.patch.dict(os.environ, env, clear=True):
        return MODULE.main()


class ClaAgreementTest(unittest.TestCase):
    def test_statement_matches_cla_document(self) -> None:
        cla = (Path(__file__).resolve().parents[2] / "CLA.md").read_text(encoding="utf-8")
        self.assertIn(f"> {STATEMENT}.", cla)

    def test_accepted_forms(self) -> None:
        bodies = (
            f"## Checklist\n\n- [x] {STATEMENT}\n- [ ] `just task-check` passes\n",
            f"- [X] {STATEMENT}",
            f"* [x] {STATEMENT}",
            f"Summary.\r\n\r\n{STATEMENT}.\r\n",
            f"> {STATEMENT}.",
            f"  {STATEMENT}  ",
        )
        for body in bodies:
            with self.subTest(body=body), mock.patch("builtins.print"):
                self.assertEqual(run("contributor", body), 0)

    def test_rejected_forms(self) -> None:
        bodies = (
            None,
            "",
            f"- [ ] {STATEMENT}",
            f"- [] {STATEMENT}",
            "I agree to the CLA.",
            f"I do not think {STATEMENT}",
            STATEMENT.replace("RIPDPI", "Other"),
        )
        for body in bodies:
            with self.subTest(body=body), mock.patch("builtins.print"):
                self.assertEqual(run("contributor", body), 1)

    def test_exempt_authors(self) -> None:
        for author in ("po4yka", "dependabot[bot]", "renovate[bot]", "github-actions[bot]"):
            with self.subTest(author=author), mock.patch("builtins.print"):
                self.assertEqual(run(author, ""), 0)

    def test_owner_lookalike_is_not_exempt(self) -> None:
        with mock.patch("builtins.print"):
            self.assertEqual(run("po4yka-fork", ""), 1)
            self.assertEqual(run("bot", ""), 1)


if __name__ == "__main__":
    unittest.main()
