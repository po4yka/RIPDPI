import shutil
import unittest
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from tempfile import TemporaryDirectory

from scripts.ci._common import REPO_ROOT
from scripts.ci.check_vendored_deploy_contracts import CONTRACT_DIR, check, update


class VendoredDeployContractsTest(unittest.TestCase):
    def test_current_vendored_files_match_the_manifest(self) -> None:
        self.assertEqual([], check())

    def test_edited_vendored_file_fails(self) -> None:
        with vendored_copy() as root:
            (root / CONTRACT_DIR / "client-field-report.schema.json").write_text("{}\n", encoding="utf-8")

            failures = check(root)

        self.assertIn("vendored: client-field-report.schema.json differs from its pinned SHA-256", failures)

    def test_unpinned_negative_fails(self) -> None:
        with vendored_copy() as root:
            (root / CONTRACT_DIR / "negative/report-new-case.json").write_text("{}\n", encoding="utf-8")

            failures = check(root)

        self.assertIn("vendored: negative/report-new-case.json is not pinned in vendored-deploy-contracts.sha256", failures)

    def test_fixture_added_by_the_deployer_fails_until_revendored(self) -> None:
        with vendored_copy() as root, TemporaryDirectory() as source_dir:
            source = Path(source_dir)
            shutil.copytree(root / CONTRACT_DIR, source, dirs_exist_ok=True)
            (source / "negative/report-new-case.json").write_text("{}\n", encoding="utf-8")

            self.assertIn(
                "deployer: negative/report-new-case.json is not pinned in vendored-deploy-contracts.sha256",
                check(root, source),
            )
            update(source, "test-revision", root)

            self.assertEqual([], check(root, source))
            self.assertTrue((root / CONTRACT_DIR / "negative/report-new-case.json").is_file())

    def test_contracts_outside_the_vendored_set_are_ignored(self) -> None:
        with vendored_copy() as root:
            (root / CONTRACT_DIR / "probe-matrix-config.schema.json").write_text("{}\n", encoding="utf-8")

            self.assertEqual([], check(root))


@contextmanager
def vendored_copy() -> Iterator[Path]:
    with TemporaryDirectory() as temp:
        root = Path(temp)
        shutil.copytree(REPO_ROOT / CONTRACT_DIR, root / CONTRACT_DIR)
        yield root


if __name__ == "__main__":
    unittest.main()
