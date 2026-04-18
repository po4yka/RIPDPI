from __future__ import annotations

import importlib.util
import json
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


class Phase16MatrixTest(unittest.TestCase):
    def test_fixture_covers_all_16_axis_combinations(self) -> None:
        fixture = phase16_matrix.load_fixture()
        phase16_matrix.validate_fixture(fixture)
        self.assertEqual("phase16_lab_matrix_v1", fixture["version"])
        self.assertEqual(16, len(fixture["entries"]))

    def test_emit_github_matrix_preserves_runner_labels(self) -> None:
        fixture = phase16_matrix.load_fixture()
        payload = phase16_matrix.emit_github_matrix(phase16_matrix.filtered_entries(fixture, "wifi_ipv4_rooted_proxy"))
        self.assertEqual(1, len(payload["include"]))
        entry = payload["include"][0]
        self.assertEqual("wifi_ipv4_rooted_proxy", entry["id"])
        self.assertEqual("raw", entry["captureMode"])
        self.assertEqual(
            ["self-hosted", "ripdpi-lab", "android", "wifi", "ipv4", "rooted"],
            json.loads(entry["runsOnJson"]),
        )

    def test_filtered_entries_rejects_unknown_filter(self) -> None:
        fixture = phase16_matrix.load_fixture()
        with self.assertRaisesRegex(ValueError, "no matrix entries matched filter"):
            phase16_matrix.filtered_entries(fixture, "does_not_exist")


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


if __name__ == "__main__":
    unittest.main()
