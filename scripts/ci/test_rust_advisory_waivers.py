import datetime as dt
import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("check_rust_advisory_waivers.py")
SPEC = importlib.util.spec_from_file_location("check_rust_advisory_waivers", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class AdvisoryWaiverValidationTest(unittest.TestCase):
    def test_repository_policy_is_complete_and_current(self) -> None:
        repo_root = Path(__file__).resolve().parents[2]
        errors = MODULE.validate(
            repo_root / "native/rust/deny.toml",
            repo_root / "native/rust/advisory-waivers.toml",
            repo_root,
            dt.date(2026, 7, 13),
        )
        self.assertEqual(errors, [])

    def test_missing_and_expired_metadata_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            deny = root / "deny.toml"
            waivers = root / "advisory-waivers.toml"
            deny.write_text(
                '[advisories]\nignore = [{ id = "RUSTSEC-TEST-0001", reason = "temporary" }, { id = "RUSTSEC-TEST-0002", reason = "temporary" }, "RUSTSEC-TEST-0003"]\n',
                encoding="utf-8",
            )
            task = root / "tracking.md"
            task.write_text("tracking", encoding="utf-8")
            waivers.write_text(
                '[[waivers]]\nid = "RUSTSEC-TEST-0001"\nowner = "security"\nreason = "temporary"\nexpires = "2026-07-13"\ntracking = "tracking.md"\n',
                encoding="utf-8",
            )

            errors = MODULE.validate(deny, waivers, root, dt.date(2026, 7, 13))

        self.assertIn("advisory waiver RUSTSEC-TEST-0001 expired on 2026-07-13", errors)
        self.assertIn("advisory ignore RUSTSEC-TEST-0002 has no owner/expiry metadata", errors)
        self.assertIn("deny.toml advisories.ignore[2] must be a table with id and reason", errors)


if __name__ == "__main__":
    unittest.main()
