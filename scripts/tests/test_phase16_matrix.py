from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


def load_module(module_name: str, relative_path: str):
    root = Path(__file__).resolve().parents[2]
    module_path = root / relative_path
    spec = importlib.util.spec_from_file_location(module_name, module_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


phase16_matrix = load_module("phase16_matrix", "scripts/ci/phase16_matrix.py")
phase16_pcap_summary = load_module("phase16_pcap_summary", "scripts/ci/phase16_pcap_summary.py")
REPO_ROOT = Path(__file__).resolve().parents[2]


class Phase16MatrixTest(unittest.TestCase):
    def test_fixture_covers_all_baseline_axis_combinations_and_stress_rows(self) -> None:
        fixture = phase16_matrix.load_fixture()
        phase16_matrix.validate_fixture(fixture)
        self.assertEqual("phase16_lab_matrix_v1", fixture["version"])
        baseline_entries = [
            entry
            for entry in fixture["entries"]
            if entry["networkCondition"] == "baseline" and phase16_matrix.entry_runner_required(entry) == "lab"
        ]
        self.assertEqual(16, len(baseline_entries))
        self.assertGreaterEqual(len(fixture["entries"]), 23)
        stress_ids = {entry["id"] for entry in fixture["entries"] if entry["networkCondition"] != "baseline"}
        self.assertTrue(phase16_matrix.REQUIRED_STRESS_ENTRIES.issubset(stress_ids))
        conditions = {entry["networkCondition"] for entry in fixture["entries"]}
        self.assertTrue(phase16_matrix.REQUIRED_NETWORK_CONDITIONS.issubset(conditions))
        real_provider_namespaces = {
            entry["carrierNamespace"]
            for entry in fixture["entries"]
            if phase16_matrix.entry_runner_required(entry) == "real-provider"
        }
        self.assertEqual(phase16_matrix.REQUIRED_REAL_PROVIDER_NAMESPACES, real_provider_namespaces)

    def test_emit_github_matrix_preserves_runner_labels(self) -> None:
        fixture = phase16_matrix.load_fixture()
        payload = phase16_matrix.emit_github_matrix(phase16_matrix.filtered_entries(fixture, "wifi_ipv4_rooted_proxy"))
        self.assertEqual(1, len(payload["include"]))
        entry = payload["include"][0]
        self.assertEqual("wifi_ipv4_rooted_proxy", entry["id"])
        self.assertEqual("baseline", entry["networkCondition"])
        self.assertEqual("raw", entry["captureMode"])
        self.assertEqual("lab", entry["runnerRequired"])
        self.assertEqual("synthetic-lab", entry["evidenceTier"])
        self.assertEqual("", entry["carrierNamespace"])
        self.assertEqual(
            ["self-hosted", "ripdpi-lab", "android", "wifi", "ipv4", "rooted"],
            json.loads(entry["runsOnJson"]),
        )

    def test_real_provider_entries_are_opt_in_unless_explicitly_filtered(self) -> None:
        fixture = phase16_matrix.load_fixture()
        default_entries = phase16_matrix.filtered_entries(fixture)
        self.assertFalse(
            any(phase16_matrix.entry_runner_required(entry) == "real-provider" for entry in default_entries)
        )
        all_entries = phase16_matrix.filtered_entries(fixture, include_real_provider=True)
        self.assertTrue(any(phase16_matrix.entry_runner_required(entry) == "real-provider" for entry in all_entries))
        filtered = phase16_matrix.filtered_entries(fixture, "real_provider_mts_cellular_ipv4_nonroot_vpn")
        self.assertEqual(1, len(filtered))
        self.assertEqual("real-provider", phase16_matrix.entry_runner_required(filtered[0]))
        payload = phase16_matrix.emit_github_matrix(filtered)
        entry = payload["include"][0]
        self.assertEqual("real-provider", entry["runnerRequired"])
        self.assertEqual("real-provider", entry["evidenceTier"])
        self.assertEqual("ns-mts", entry["carrierNamespace"])

    def test_filtered_entries_rejects_unknown_filter(self) -> None:
        fixture = phase16_matrix.load_fixture()
        with self.assertRaisesRegex(ValueError, "no matrix entries matched filter"):
            phase16_matrix.filtered_entries(fixture, "does_not_exist")

    def test_matrix_scenario_filters_exist_in_packet_smoke_registry(self) -> None:
        fixture = phase16_matrix.load_fixture()
        registry = json.loads((REPO_ROOT / "scripts/ci/packet-smoke-scenarios.json").read_text(encoding="utf-8"))
        scenario_ids = {entry["id"] for entry in registry}
        missing = sorted({entry["scenarioFilter"] for entry in fixture["entries"]} - scenario_ids)
        self.assertEqual([], missing)

    def test_real_provider_runner_fails_closed_without_runner_config(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            artifact_root = Path(temp_dir) / "artifacts"
            env = os.environ.copy()
            env.update(
                {
                    "PHASE16_ENTRY_ID": "real_provider_mts_cellular_ipv4_nonroot_vpn",
                    "PHASE16_EXECUTION_KIND": "android_packet_smoke",
                    "PHASE16_TRANSPORT": "cellular",
                    "PHASE16_IP_FAMILY": "ipv4",
                    "PHASE16_ROOTED": "false",
                    "PHASE16_MODE": "vpn",
                    "PHASE16_NETWORK_CONDITION": "baseline",
                    "PHASE16_SCENARIO_FILTER": "android_vpn_tunnel_baseline_family",
                    "PHASE16_CAPTURE_MODE": "indirect",
                    "PHASE16_RUNNER_REQUIRED": "real-provider",
                    "PHASE16_EVIDENCE_TIER": "real-provider",
                    "PHASE16_CARRIER_NAMESPACE": "ns-mts",
                    "RIPDPI_PHASE16_ARTIFACT_DIR": str(artifact_root),
                }
            )
            result = subprocess.run(
                ["bash", str(REPO_ROOT / "scripts/ci/run-phase16-matrix-entry.sh")],
                check=False,
                env=env,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("RIPDPI_PHASE16_REAL_PROVIDER_CONFIG", result.stderr)
            manifest = json.loads((artifact_root / "phase16-run.json").read_text(encoding="utf-8"))
            self.assertEqual("failure", manifest["status"])
            self.assertEqual("real-provider", manifest["runnerRequired"])
            self.assertEqual("real-provider", manifest["evidenceTier"])
            self.assertEqual("ns-mts", manifest["carrierNamespace"])
            summary = json.loads((artifact_root / "phase16-pcap-summary.json").read_text(encoding="utf-8"))
            self.assertEqual("real-provider", summary["runMetadata"]["runnerRequired"])
            self.assertEqual("real-provider", summary["runMetadata"]["evidenceTier"])
            self.assertEqual("ns-mts", summary["runMetadata"]["carrierNamespace"])


class Phase16PcapSummaryTest(unittest.TestCase):
    def test_summary_collects_expected_artifacts_and_packet_signals(self) -> None:
        registry = phase16_pcap_summary.load_registry()
        with tempfile.TemporaryDirectory() as temp_dir:
            scenario_dir = Path(temp_dir) / "cli_packet_smoke_tcp_disoob_family"
            scenario_dir.mkdir(parents=True)
            (scenario_dir / "fixture-manifest.json").write_text("{}", encoding="utf-8")
            (scenario_dir / "fixture-events.json").write_text("[]", encoding="utf-8")
            (scenario_dir / "cli-stderr.log").write_text("stderr", encoding="utf-8")
            (scenario_dir / "test-output.txt").write_text("output", encoding="utf-8")
            (scenario_dir / "capture.pcap").write_bytes(b"pcap")
            (scenario_dir / "capture.tshark.json").write_text(
                json.dumps(
                    [
                        {
                            "_source": {
                                "layers": {
                                    "ip.ttl": {"show": "3"},
                                    "tcp.flags.urg": {"show": "1"},
                                    "tcp.dstport": {"show": "46001"},
                                }
                            }
                        },
                        {
                            "_source": {
                                "layers": {
                                    "udp.srcport": {"show": "4095"},
                                    "quic.version": {"show": "0x1a2b3c4d"},
                                }
                            }
                        },
                    ]
                ),
                encoding="utf-8",
            )
            summary = phase16_pcap_summary.summarize_artifact_root(Path(temp_dir), registry)
            self.assertEqual("phase16_pcap_summary_v1", summary["version"])
            self.assertEqual(1, summary["scenarioCount"])
            scenario = summary["scenarios"][0]
            self.assertEqual("cli_packet_smoke_tcp_disoob_family", scenario["id"])
            self.assertIn("capture.tshark.json", scenario["presentArtifacts"])
            self.assertEqual([], scenario["missingArtifacts"])
            capture = scenario["captureSummary"]
            self.assertEqual(2, capture["packetCount"])
            self.assertEqual([3], capture["ipv4Ttls"])
            self.assertEqual(1, capture["tcpUrgentPackets"])
            self.assertEqual(["0x1a2b3c4d"], capture["quicVersions"])
            self.assertEqual([4095], capture["udpSourcePorts"])

    def test_summary_reads_android_device_capture_artifact(self) -> None:
        registry = phase16_pcap_summary.load_registry()
        with tempfile.TemporaryDirectory() as temp_dir:
            scenario_dir = Path(temp_dir) / "android_vpn_tunnel_baseline_family"
            scenario_dir.mkdir(parents=True)
            (scenario_dir / "fixture-manifest.json").write_text("{}", encoding="utf-8")
            (scenario_dir / "fixture-events.json").write_text("[]", encoding="utf-8")
            (scenario_dir / "logcat.txt").write_text("logcat", encoding="utf-8")
            (scenario_dir / "dumpsys-connectivity.txt").write_text("dumpsys", encoding="utf-8")
            (scenario_dir / "ip-addr.txt").write_text("ip addr", encoding="utf-8")
            (scenario_dir / "ip-route.txt").write_text("ip route", encoding="utf-8")
            (scenario_dir / "test-output.txt").write_text("output", encoding="utf-8")
            (scenario_dir / "prepare-state.json").write_text("{}", encoding="utf-8")
            (scenario_dir / "runner-probe.json").write_text("{}", encoding="utf-8")
            (scenario_dir / "runner-probe-command.txt").write_text("probe", encoding="utf-8")
            (scenario_dir / "failure-screenshot.png").write_bytes(b"png")
            (scenario_dir / "device-capture.pcap").write_bytes(b"pcap")
            (scenario_dir / "device-capture.tshark.json").write_text(
                json.dumps(
                    [
                        {
                            "_source": {
                                "layers": {
                                    "ip.ttl": {"show": "64"},
                                    "udp.srcport": {"show": "53000"},
                                    "quic.version": {"show": "0x00000001"},
                                }
                            }
                        }
                    ]
                ),
                encoding="utf-8",
            )

            summary = phase16_pcap_summary.summarize_artifact_root(Path(temp_dir), registry)
            scenario = summary["scenarios"][0]
            self.assertEqual("android_vpn_tunnel_baseline_family", scenario["id"])
            self.assertIn("device-capture.pcap", scenario["presentArtifacts"])
            self.assertIn("device-capture.tshark.json", scenario["presentArtifacts"])
            self.assertEqual([], scenario["missingArtifacts"])
            capture = scenario["captureSummary"]
            self.assertEqual(1, capture["packetCount"])
            self.assertEqual([64], capture["ipv4Ttls"])
            self.assertEqual(["0x00000001"], capture["quicVersions"])
            self.assertEqual([53000], capture["udpSourcePorts"])

    def test_successful_indirect_android_run_does_not_require_capture_or_failure_screenshot(self) -> None:
        registry = phase16_pcap_summary.load_registry()
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "phase16-run.json").write_text(
                json.dumps({"status": "success", "captureMode": "indirect"}),
                encoding="utf-8",
            )
            shared_dir = root / "shared"
            shared_dir.mkdir()
            (shared_dir / "fixture-manifest.json").write_text("{}", encoding="utf-8")
            scenario_dir = root / "android_proxy_tlsrec_family"
            scenario_dir.mkdir()
            for artifact_name in [
                "fixture-manifest.json",
                "fixture-events.json",
                "logcat.txt",
                "dumpsys-connectivity.txt",
                "ip-addr.txt",
                "ip-route.txt",
                "test-output.txt",
            ]:
                (scenario_dir / artifact_name).write_text("", encoding="utf-8")

            summary = phase16_pcap_summary.summarize_artifact_root(root, registry)
            self.assertEqual("lab", summary["runMetadata"]["runnerRequired"])
            self.assertEqual("synthetic-lab", summary["runMetadata"]["evidenceTier"])
            self.assertEqual(1, summary["scenarioCount"])
            scenario = summary["scenarios"][0]
            self.assertEqual("android_proxy_tlsrec_family", scenario["id"])
            self.assertEqual([], scenario["missingArtifacts"])
            self.assertEqual(["device-capture.pcap", "failure-screenshot.png"], scenario["optionalArtifacts"])


if __name__ == "__main__":
    unittest.main()
