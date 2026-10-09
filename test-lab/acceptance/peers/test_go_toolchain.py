"""Toolchain selection regressions; these are not protocol acceptance tests."""

import importlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
toolchain = importlib.import_module("go_toolchain")


class GoToolchainTest(unittest.TestCase):
    def fixture(self, directory):
        root = (Path(directory) / "go-root").resolve()
        (root / "bin").mkdir(parents=True)
        (root / "bin/go").write_bytes(b"test-only binary identity")
        selected = json.dumps({"GOROOT": str(root), "GOVERSION": "go1.27.0"})
        return root, selected

    def test_prepare_records_exact_resolved_binary(self):
        with tempfile.TemporaryDirectory() as directory:
            root, selected = self.fixture(directory)
            cache = Path(directory) / "cache"
            with patch.object(
                toolchain.subprocess, "check_output", return_value=selected
            ) as run:
                identity = toolchain.prepare(cache, "go1.27.0", root)
            self.assertEqual(toolchain.digest(root / "bin/go"), identity["sha256"])
            self.assertEqual("go1.27.0", run.call_args.kwargs["env"]["GOTOOLCHAIN"])
            self.assertEqual(
                identity,
                json.loads(toolchain.metadata_path(cache, "go1.27.0").read_text()),
            )

    def test_offline_resolve_bypasses_bootstrap_toolchain_switcher(self):
        with tempfile.TemporaryDirectory() as directory:
            root, selected = self.fixture(directory)
            cache = Path(directory) / "cache"
            with patch.object(
                toolchain.subprocess, "check_output", return_value=selected
            ):
                toolchain.prepare(cache, "go1.27.0", root)
            with patch.object(
                toolchain.subprocess, "check_output", return_value=selected
            ) as run:
                env, identity = toolchain.resolve(cache, "go1.27.0")
            self.assertEqual(str(root / "bin/go"), run.call_args.args[0][0])
            self.assertEqual("local", run.call_args.kwargs["env"]["GOTOOLCHAIN"])
            self.assertEqual(str(root / "bin"), env["PATH"].split(os.pathsep)[0])
            self.assertEqual(str(root), env["GOROOT"])
            self.assertEqual("off", env["GOPROXY"])
            self.assertEqual("off", env["GOSUMDB"])
            self.assertEqual("go1.27.0", identity["version"])

    def test_unprepared_toolchain_fails_without_running_go(self):
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(toolchain.subprocess, "check_output") as run,
        ):
            with self.assertRaises(FileNotFoundError):
                toolchain.resolve(Path(directory), "go1.27.0")
            run.assert_not_called()

    def test_tampered_binary_fails_before_execution(self):
        with tempfile.TemporaryDirectory() as directory:
            root, selected = self.fixture(directory)
            cache = Path(directory) / "cache"
            with patch.object(
                toolchain.subprocess, "check_output", return_value=selected
            ):
                toolchain.prepare(cache, "go1.27.0", root)
            (root / "bin/go").write_bytes(b"changed")
            with patch.object(toolchain.subprocess, "check_output") as run:
                with self.assertRaisesRegex(ValueError, "digest mismatch"):
                    toolchain.resolve(cache, "go1.27.0")
                run.assert_not_called()

    def test_prepare_rejects_version_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            root, _ = self.fixture(directory)
            with patch.object(
                toolchain.subprocess,
                "check_output",
                return_value=json.dumps({"GOROOT": str(root), "GOVERSION": "go1.27.1"}),
            ):
                with self.assertRaisesRegex(ValueError, "differs from the oracle pin"):
                    toolchain.prepare(Path(directory) / "cache", "go1.27.0", root)

    def test_outbound_pin_matches_reused_oracle(self):
        repository = Path(__file__).resolve().parents[3]
        source = (repository / "scripts/tests/run-outbound-interop.py").read_text()
        self.assertIn(f'GOTOOLCHAIN="{toolchain.OUTBOUND_VERSION}"', source)


if __name__ == "__main__":
    unittest.main()
