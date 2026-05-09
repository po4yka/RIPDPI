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


class RuntimeDecisionPortsTests(unittest.TestCase):
    def test_explicit_port_exports_pass(self) -> None:
        source = """
pub use ripdpi_runtime_adaptive::morph_policy::{
    apply_tcp_morph_policy_to_group, apply_udp_morph_policy_to_hints,
};
pub use ripdpi_runtime_policy::{
    ConnectionRoute, DirectPathLearningObserver, DirectPathLearningPort, PolicyPort,
};
"""
        violations = sut.runtime_decision_ports_violations(sut.RUNTIME_DECISION_PORTS_PATH, source)
        self.assertEqual(violations, [])

    def test_broad_module_shortcuts_fail(self) -> None:
        source = """
pub mod adaptive {
    pub use ripdpi_runtime_adaptive::strategy_context;
}
pub mod policy {
    pub use ripdpi_runtime_policy::runtime_policy::*;
}
"""
        violations = sut.runtime_decision_ports_violations(sut.RUNTIME_DECISION_PORTS_PATH, source)
        messages = [violation.message for violation in violations]
        self.assertTrue(any("broad module `adaptive`" in message for message in messages))
        self.assertTrue(any("broad module `policy`" in message for message in messages))

    def test_engine_state_exports_fail(self) -> None:
        source = """
pub use ripdpi_runtime_policy::{DirectPathLearningState, RuntimePolicy};
"""
        violations = sut.runtime_decision_ports_violations(sut.RUNTIME_DECISION_PORTS_PATH, source)
        messages = [violation.message for violation in violations]
        self.assertTrue(any("runtime policy engine type export" in message for message in messages))
        self.assertTrue(any("direct-path learning state export" in message for message in messages))


class DiagnosticsLaneContractTests(unittest.TestCase):
    def test_explicit_diagnostics_adapter_exports_pass(self) -> None:
        source = """
pub(crate) mod dns {
    pub use ripdpi_diagnostics_dns::dns::{resolve_via_udp_with_raw, resolve_via_encrypted_dns};
}
"""
        violations = sut.diagnostics_lane_adapter_violations(
            Path("native/rust/crates/ripdpi-diagnostics-runner/src/connectivity/adapters.rs"),
            source,
        )
        self.assertEqual(violations, [])

    def test_broad_diagnostics_adapter_exports_fail(self) -> None:
        source = """
pub(crate) mod dns {
    pub use ripdpi_diagnostics_dns::dns::*;
}
use crate::connectivity::adapters::dns::*;
"""
        violations = sut.diagnostics_lane_adapter_violations(
            Path("native/rust/crates/ripdpi-diagnostics-runner/src/connectivity/adapters.rs"),
            source,
        )
        messages = [violation.message for violation in violations]
        self.assertTrue(any("explicit symbols" in message for message in messages))
        self.assertTrue(any("broad lane adapter globs" in message for message in messages))

    def test_monitor_engine_concrete_lane_dependency_fails(self) -> None:
        source = """
[dependencies]
ripdpi-diagnostics-contracts = { workspace = true }
ripdpi-diagnostics-dns = { workspace = true }
"""
        violations = sut.monitor_engine_dependency_violations(sut.MONITOR_ENGINE_CARGO_PATH, source)
        self.assertEqual(len(violations), 1)
        self.assertIn("ripdpi-diagnostics-dns", violations[0].message)


class RepoCollectionTests(unittest.TestCase):
    def test_collect_violations_reads_repo_layout(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            self._write(
                repo_root / "native/rust/crates/ripdpi-android-proxy-adapter/src/lib.rs",
                "pub(crate) fn proxy_start_entry() {}\n",
            )
            self._write(
                repo_root / "native/rust/crates/ripdpi-android-diagnostics-adapter/src/lib.rs",
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
            self._write(
                repo_root / "native/rust/crates/ripdpi-runtime-decision-ports/src/lib.rs",
                "pub use ripdpi_runtime_policy::{ConnectionRoute, PolicyPort};\n",
            )
            self._write(
                repo_root / "native/rust/crates/ripdpi-diagnostics-runner/src/connectivity/adapters.rs",
                "pub(crate) mod dns { pub use ripdpi_diagnostics_dns::dns::resolve_via_udp_with_raw; }\n",
            )
            self._write(
                repo_root / "native/rust/crates/ripdpi-diagnostics-runner/src/strategy/adapters.rs",
                "pub(crate) mod dns { pub use ripdpi_diagnostics_dns::dns::resolve_via_encrypted_dns; }\n",
            )
            self._write(
                repo_root / "native/rust/crates/ripdpi-monitor-engine/Cargo.toml",
                "ripdpi-diagnostics-contracts = { workspace = true }\n",
            )

            violations = sut.collect_violations(repo_root)
            self.assertEqual(violations, [])

    @staticmethod
    def _write(path: Path, text: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
