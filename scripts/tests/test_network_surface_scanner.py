from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCANNER_PATH = (
    REPO_ROOT
    / ".agents"
    / "skills"
    / "ripdpi-app-network-architect"
    / "scripts"
    / "analyze_app_network_surface.py"
)
SPEC = importlib.util.spec_from_file_location("network_surface_scanner", SCANNER_PATH)
assert SPEC is not None and SPEC.loader is not None
SCANNER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = SCANNER
SPEC.loader.exec_module(SCANNER)


class NetworkSurfaceScannerTest(unittest.TestCase):
    def test_default_scan_excludes_coding_harness_content(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            excluded = (
                ".agents/skills/example/SKILL.md",
                ".claude/agents/example.md",
                ".codex/agents/example.toml",
                ".github/workflows/example.yml",
                "AGENTS.md",
                "CLAUDE.md",
                "core/service/AGENTS.md",
            )
            for relative_path in excluded:
                self._write(root / relative_path, "pcap payload QUIC resolver\n")
            included = root / "core/service/src/main/kotlin/example/SocketFactory.kt"
            self._write(included, "fun open() = Socket()\n")

            paths = {
                path.relative_to(root).as_posix()
                for path in SCANNER.iter_files(root, set(SCANNER.DEFAULT_EXCLUDES))
            }

        self.assertEqual({included.relative_to(root).as_posix()}, paths)

    @staticmethod
    def _write(path: Path, text: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
