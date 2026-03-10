#!/usr/bin/env python3

from __future__ import annotations

import argparse
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


PROJECT_ROOT = Path()
SOURCE_BINARY = Path()


def run_command(args: list[str], *, cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        args,
        cwd=cwd,
        capture_output=True,
        text=True,
        check=False,
    )


class InstallCutoverTests(unittest.TestCase):
    def test_make_install_ships_default_rust_binary_only(self) -> None:
        self.assertTrue(SOURCE_BINARY.exists(), SOURCE_BINARY)

        with tempfile.TemporaryDirectory(prefix="ciadpi-install-") as tmpdir:
            destdir = Path(tmpdir)
            proc = run_command(
                ["make", "install", f"DESTDIR={destdir}"],
                cwd=PROJECT_ROOT,
            )
            self.assertEqual(
                proc.returncode,
                0,
                f"make install failed\nstdout:\n{proc.stdout}\nstderr:\n{proc.stderr}",
            )

            install_dir = destdir / "usr" / "local" / "bin"
            installed = install_dir / "ciadpi"
            self.assertTrue(installed.exists(), proc.stdout + proc.stderr)
            self.assertTrue(os.access(installed, os.X_OK), installed)

            self.assertEqual(installed.read_bytes(), SOURCE_BINARY.read_bytes())

            source_version = run_command([str(SOURCE_BINARY), "--version"])
            installed_version = run_command([str(installed), "--version"])
            self.assertEqual(source_version.returncode, 0, source_version.stderr)
            self.assertEqual(installed_version.returncode, 0, installed_version.stderr)
            self.assertEqual(installed_version.stdout.strip(), source_version.stdout.strip())

            installed_entries = sorted(path.name for path in install_dir.iterdir())
            self.assertEqual(installed_entries, ["ciadpi"])

            oracle_paths = sorted(path.relative_to(destdir) for path in destdir.rglob("*oracle*"))
            self.assertEqual(oracle_paths, [], oracle_paths)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--source-binary", required=True)
    args = parser.parse_args()

    global PROJECT_ROOT, SOURCE_BINARY
    PROJECT_ROOT = Path(args.project_root).resolve()
    SOURCE_BINARY = Path(args.source_binary).resolve()

    suite = unittest.defaultTestLoader.loadTestsFromTestCase(InstallCutoverTests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
