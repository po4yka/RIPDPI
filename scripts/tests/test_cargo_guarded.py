from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "ci" / "cargo-guarded.sh"


class CargoGuardedTest(unittest.TestCase):
    def test_child_git_initialization_cannot_reinitialize_hook_repository(self) -> None:
        for gated in (True, False):
            with self.subTest(gated=gated), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary).resolve()
                environment = {
                    key: value for key, value in os.environ.items()
                    if not key.startswith("GIT_") and not key.startswith("BUILD_GATE_")
                }
                environment.update(
                    GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull,
                    GIT_AUTHOR_NAME="Fixture", GIT_AUTHOR_EMAIL="fixture@example.com",
                    GIT_COMMITTER_NAME="Fixture", GIT_COMMITTER_EMAIL="fixture@example.com",
                )

                def git(*arguments: str) -> str:
                    return subprocess.check_output(
                        ["git", *arguments], env=environment, text=True,
                        stderr=subprocess.DEVNULL,
                    ).strip()

                repository = directory / "repository"
                linked = directory / "linked tree"
                git("init", "--initial-branch=main", str(repository))
                git("-C", str(repository), "-c", "core.hooksPath=/dev/null",
                    "commit", "--allow-empty", "-m", "Fixture")
                git("-C", str(repository), "worktree", "add", "-b", "hook", str(linked))
                git_directory = git("-C", str(linked), "rev-parse", "--absolute-git-dir")
                config = repository / ".git" / "config"
                before = config.read_bytes()
                build = directory / "compiler child"
                build.mkdir()
                binaries = directory / "bin"
                binaries.mkdir()
                cargo = binaries / "cargo"
                cargo.write_text(
                    f"#!{sys.executable}\n"
                    "import json, os, subprocess, sys\n"
                    "from pathlib import Path\n"
                    "Path(os.environ['PROBE_RECEIPT']).write_text(json.dumps({\n"
                    " 'args': sys.argv[1:],\n"
                    " 'repositoryEnvironment': {key: os.environ.get(key) for key in\n"
                    "  ['GIT_DIR', 'GIT_WORK_TREE', 'GIT_COMMON_DIR', 'GIT_INDEX_FILE', 'GIT_PREFIX']}\n"
                    "}))\n"
                    "subprocess.run(['git', 'init'], cwd=os.environ['PROBE_BUILD'], check=True,\n"
                    " stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)\n",
                    encoding="utf-8",
                )
                cargo.chmod(0o755)
                gate = binaries / "build-gate"
                gate.write_text(
                    "#!/bin/sh\nprintf invoked > \"$PROBE_GATE\"\n"
                    "test \"$1\" = -- || exit 64\nshift\nexec \"$@\"\n",
                    encoding="utf-8",
                )
                gate.chmod(0o755)
                receipt = directory / "receipt.json"
                gate_receipt = directory / "gate.txt"
                environment.update(
                    PATH=str(binaries) + os.pathsep + environment["PATH"],
                    BUILD_GATE_REAL_CARGO=str(cargo), BUILD_GATE_DISABLED="0" if gated else "1",
                    PROBE_RECEIPT=str(receipt), PROBE_BUILD=str(build), PROBE_GATE=str(gate_receipt),
                    GIT_DIR=git_directory, GIT_INDEX_FILE=git_directory + "/index", GIT_PREFIX="fixture/",
                )
                arguments = ["clippy", "--locked", "--workspace", "--all-targets", "--", "-D", "warnings"]
                result = subprocess.run(
                    ["bash", str(SCRIPT), "cargo", *arguments], cwd=linked,
                    env=environment, capture_output=True, text=True, check=False,
                )
                self.assertEqual(0, result.returncode, result.stderr)
                actual = json.loads(receipt.read_text(encoding="utf-8"))
                self.assertEqual(arguments, actual["args"])
                self.assertEqual(before, config.read_bytes(), "child git init changed the hook repository")
                self.assertTrue(all(value is None for value in actual["repositoryEnvironment"].values()))
                self.assertTrue((build / ".git").is_dir(), "child did not initialize its own repository")
                self.assertEqual(gated, gate_receipt.exists())


if __name__ == "__main__":
    unittest.main()
