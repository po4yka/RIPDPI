#!/usr/bin/env python3

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_native_architecture_contracts as sut


class WsTransportLayerContractTests(unittest.TestCase):
    def test_ws_transport_layer_contract_rejects_concrete_coupling(self) -> None:
        bad_graph = {
            "ripdpi-ws-transport-port": {"ripdpi-runtime-platform"},
            "ripdpi-ws-bootstrap": {"ripdpi-ws-tunnel"},
            "ripdpi-diagnostics-telegram": {"ripdpi-ws-tunnel"},
            "ripdpi-ws-tunnel": set(),
        }
        manifest_paths = {
            crate: Path(f"native/rust/crates/{crate}/Cargo.toml")
            for crate in bad_graph
        }

        bad_violations = sut.ws_transport_layer_violations(bad_graph, manifest_paths)
        bad_messages = [violation.message for violation in bad_violations]

        self.assertEqual(len(bad_violations), 6)
        self.assertTrue(any("dependency-free contract boundary" in message for message in bad_messages))
        self.assertEqual(sum("must not depend on ripdpi-ws-tunnel" in message for message in bad_messages), 2)
        self.assertEqual(sum("must not depend on ripdpi-ws-transport-port" in message for message in bad_messages), 3)

        graph, manifest_paths = sut.production_dependency_graph(sut.REPO_ROOT)

        violations = sut.ws_transport_layer_violations(graph, manifest_paths)

        self.assertEqual(
            violations,
            [],
            "WsTransport layer contract retains concrete ripdpi-ws-tunnel coupling:\n"
            + "\n".join(f"{violation.path}: {violation.message}" for violation in violations),
        )


if __name__ == "__main__":
    unittest.main()
