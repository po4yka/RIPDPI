#!/usr/bin/env python3
"""Run every pinned upstream outbound oracle; macOS callers must use build-gate."""

import os
from pathlib import Path
import runpy
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / "scripts/tests/run-outbound-interop.py"
REPOSITORIES = {
    "ssh": "https://github.com/enfein/mieru.git",
    "mieru": "https://github.com/enfein/mieru.git",
    "anytls": "https://github.com/anytls/anytls-go.git",
}


def run_cases(runner, directory: Path, env: dict[str, str]) -> None:
    checked = runner["checked"]
    sources = {}
    for protocol, cases in runner["TESTS"].items():
        revision = runner["REVISIONS"][protocol]
        source_key = (REPOSITORIES[protocol], revision)
        if source_key not in sources:
            source = directory / protocol
            source.mkdir()
            checked(["git", "init", "--quiet"], source, env, limit=30)
            checked(["git", "fetch", "--quiet", "--depth=1", source_key[0], revision], source, env, limit=120)
            checked(["git", "checkout", "--quiet", "--detach", "FETCH_HEAD"], source, env, limit=30)
            sources[source_key] = source
        for case in cases:
            # Keep peer/compiler cleanup in this process's finally blocks, not
            # behind a second process-group timeout that could orphan children.
            runner["run"](["--protocol", protocol, "--source-dir", str(sources[source_key]), "--test", case])


def main() -> None:
    if sys.platform == "darwin" and os.environ.get("BUILD_GATE_HELD") != "1":
        raise RuntimeError("run this compiler-backed suite through build-gate")
    runner = runpy.run_path(str(RUNNER))
    with tempfile.TemporaryDirectory(prefix="ripdpi-outbound-upstream-") as directory:
        run_cases(runner, Path(directory), dict(os.environ))


if __name__ == "__main__":
    main()
