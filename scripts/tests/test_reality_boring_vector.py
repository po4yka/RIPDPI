#!/usr/bin/env python3
"""Unit tests for scripts/ci/check_reality_boring_vector.py."""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


def load_module():
    root = Path(__file__).resolve().parents[2]
    module_path = root / "scripts/ci/check_reality_boring_vector.py"
    spec = importlib.util.spec_from_file_location("check_reality_boring_vector", module_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


guard = load_module()


class RealityBoringVectorTest(unittest.TestCase):
    def test_repo_versions_match_vector(self) -> None:
        self.assertEqual({"boring": "=5.1.0", "tokio-boring": "=5.1.0"}, guard.validate())

    def test_caret_workspace_version_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            cargo = Path(tmp) / "Cargo.toml"
            vector = Path(tmp) / "vector.json"
            cargo.write_text('boring = "5.1.0"\ntokio-boring = "=5.0.0"\n', encoding="utf-8")
            vector.write_text(
                json.dumps({"boring": "=5.1.0", "tokio-boring": "=5.0.0", "fixedNowSecs": 1_700_000_000, "shortIdHex": "abcd"}),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "exact Cargo pin"):
                guard.validate(cargo, vector)

    def test_dependency_vector_mismatch_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            cargo = Path(tmp) / "Cargo.toml"
            vector = Path(tmp) / "vector.json"
            cargo.write_text('boring = "=5.2.0"\ntokio-boring = "=5.0.0"\n', encoding="utf-8")
            vector.write_text(
                json.dumps({"boring": "=5.1.0", "tokio-boring": "=5.0.0", "fixedNowSecs": 1_700_000_000, "shortIdHex": "abcd"}),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "vector must be updated"):
                guard.validate(cargo, vector)

    def test_vector_constant_mismatch_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            cargo = Path(tmp) / "Cargo.toml"
            vector = Path(tmp) / "vector.json"
            cargo.write_text('boring = "=5.1.0"\ntokio-boring = "=5.0.0"\n', encoding="utf-8")
            vector.write_text(
                json.dumps({"boring": "=5.1.0", "tokio-boring": "=5.0.0", "fixedNowSecs": 42, "shortIdHex": "abcd"}),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "fixedNowSecs"):
                guard.validate(cargo, vector)


if __name__ == "__main__":
    unittest.main()
