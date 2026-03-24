#!/usr/bin/env python3

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_native_architecture_contracts as sut


class AdapterContractTests(unittest.TestCase):
    def test_compliant_adapter_file_passes(self) -> None:
        source = """
mod lifecycle;

use jni::JNIEnv;

pub(crate) fn proxy_start_entry(mut env: JNIEnv) {}
"""
        violations = sut.adapter_contract_violations(Path("native/rust/crates/ripdpi-android/src/proxy.rs"), source)
        self.assertEqual(violations, [])

    def test_helper_function_in_adapter_fails(self) -> None:
        source = """
pub(crate) fn proxy_start_entry() {}
fn helper() {}
"""
        violations = sut.adapter_contract_violations(Path("native/rust/crates/ripdpi-android/src/proxy.rs"), source)
        self.assertEqual(len(violations), 1)
        self.assertIn("non-entry function `helper`", violations[0].message)

    def test_static_in_adapter_fails(self) -> None:
        source = """
pub(crate) static SESSIONS: usize = 1;
pub(crate) fn diagnostics_create_entry() {}
"""
        violations = sut.adapter_contract_violations(
            Path("native/rust/crates/ripdpi-android/src/diagnostics.rs"),
            source,
        )
        self.assertEqual(len(violations), 1)
        self.assertIn("forbidden top-level static item", violations[0].message)


class ConfigOwnershipTests(unittest.TestCase):
    def test_model_with_parse_function_fails(self) -> None:
        source = """
pub fn parse_something() {}
"""
        violations = sut.config_ownership_violations(Path("native/rust/crates/ripdpi-config/src/model.rs"), source)
        self.assertEqual(len(violations), 1)
        self.assertIn("parse-owned function `parse_something`", violations[0].message)

    def test_startup_env_outside_parse_fails(self) -> None:
        source = """
pub struct StartupEnv;

impl StartupEnv {}
"""
        violations = sut.config_ownership_violations(Path("native/rust/crates/ripdpi-config/src/model.rs"), source)
        self.assertEqual(len(violations), 1)
        self.assertIn("`StartupEnv` must live under", violations[0].message)

    def test_parse_owned_symbols_under_parse_pass(self) -> None:
        source = """
pub struct StartupEnv;

impl StartupEnv {}

pub fn parse_cli() {}
pub fn normalize_quic_fake_host() {}
pub fn data_from_str() {}
"""
        violations = sut.config_ownership_violations(
            Path("native/rust/crates/ripdpi-config/src/parse/startup_env.rs"),
            source,
        )
        self.assertEqual(violations, [])


class RepoCollectionTests(unittest.TestCase):
    def test_collect_violations_reads_repo_layout(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            self._write(
                repo_root / "native/rust/crates/ripdpi-android/src/proxy.rs",
                "pub(crate) fn proxy_start_entry() {}\n",
            )
            self._write(
                repo_root / "native/rust/crates/ripdpi-android/src/diagnostics.rs",
                "pub(crate) fn diagnostics_create_entry() {}\n",
            )
            self._write(
                repo_root / "native/rust/crates/ripdpi-tunnel-android/src/session.rs",
                "pub(crate) fn tunnel_start_entry() {}\n",
            )
            self._write(
                repo_root / "native/rust/crates/ripdpi-config/src/model.rs",
                "pub fn model_helper() {}\n",
            )
            self._write(
                repo_root / "native/rust/crates/ripdpi-config/src/parse/startup_env.rs",
                "pub struct StartupEnv;\nimpl StartupEnv {}\n",
            )

            violations = sut.collect_violations(repo_root)
            self.assertEqual(violations, [])

    @staticmethod
    def _write(path: Path, text: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
