import importlib.util
import json
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
GENERATOR_PATH = REPO_ROOT / "scripts/ci/packet-smoke-generator.py"
REGISTRY_PATH = REPO_ROOT / "scripts/ci/packet-smoke-scenarios.json"


def load_generator_module():
    spec = importlib.util.spec_from_file_location("packet_smoke_generator", GENERATOR_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class PacketSmokeGeneratorTest(unittest.TestCase):
    def test_same_seed_produces_stable_cells(self):
        generator = load_generator_module()

        first = generator.generate("pull-request-123:abc", 8, "random")
        second = generator.generate("pull-request-123:abc", 8, "random")
        different_seed = generator.generate("pull-request-124:def", 8, "random")

        self.assertEqual(first, second)
        self.assertNotEqual(
            [scenario["generator_axis_values"] for scenario in first],
            [scenario["generator_axis_values"] for scenario in different_seed],
        )
        for scenario in first:
            self.assertEqual("cli_packet_smoke_generated_cell", scenario["testSelector"])
            self.assertIn(scenario["trafficKind"], {"tcp_http", "tcp_tls", "udp_quic"})
            self.assertEqual("pull-request-123:abc", scenario["generator_seed"])
            self.assertEqual("random", scenario["generator_origin"])
            self.assertEqual(set(generator.AXES), set(scenario["generator_axis_values"]))

    def test_cli_scenario_filters_exist_in_registry(self):
        registry = json.loads(REGISTRY_PATH.read_text(encoding="utf-8"))
        ids = {entry["id"] for entry in registry}
        selectors = {entry["testSelector"] for entry in registry}

        required_filters = {
            "cli_packet_smoke_tcp_split_family",
            "cli_packet_smoke_tcp_disorder_family",
            "cli_packet_smoke_tcp_fake_family",
            "cli_packet_smoke_tcp_oob_family",
            "cli_packet_smoke_tcp_disoob_family",
            "cli_packet_smoke_tcp_transport_knobs_family",
            "cli_packet_smoke_udp_quic_family",
            "cli_packet_smoke_adaptive_family",
            "cli_packet_smoke_generated_cell",
        }

        self.assertEqual(set(), required_filters - ids)
        self.assertEqual(set(), required_filters - selectors)

    def test_generated_template_is_not_a_named_cli_scenario(self):
        registry = json.loads(REGISTRY_PATH.read_text(encoding="utf-8"))
        generated_templates = [entry for entry in registry if entry.get("generatedTemplate") is True]

        self.assertEqual(["cli_packet_smoke_generated_cell"], [entry["id"] for entry in generated_templates])
        self.assertTrue(all(entry["lane"] == "cli" for entry in generated_templates))

    def test_generated_cells_exclude_tls_oob_combinations(self):
        generator = load_generator_module()

        scenarios = generator.generate("schedule:refs/heads/main:example", 256, "random")

        self.assertEqual(
            [],
            [
                scenario
                for scenario in scenarios
                if scenario["trafficKind"] == "tcp_tls"
                and scenario["generator_axis_values"]["oob_byte_placement"] != "off"
            ],
        )

    def test_generated_cells_exclude_fake_tls_non_host_offsets(self):
        generator = load_generator_module()

        scenarios = generator.generate("schedule:refs/heads/main:example", 512, "random")

        self.assertEqual(
            [],
            [
                scenario
                for scenario in scenarios
                if scenario["trafficKind"] == "tcp_tls"
                and scenario["generator_axis_values"]["fake_ttl_ladder"] != "off"
                and scenario["generator_axis_values"]["split_offset"] not in generator.HOST_RELATIVE_SPLIT_OFFSETS
            ],
        )

    def test_latest_push_seed_does_not_emit_unsupported_fake_tls_cell(self):
        generator = load_generator_module()

        scenarios = generator.generate(
            "push:refs/heads/main:795cd09eba864b1c1a7227249f10a4e773a6b141",
            8,
            "random",
        )

        self.assertNotIn(
            "cli_packet_smoke_generated_random_003_000492d1fb",
            [scenario["id"] for scenario in scenarios],
        )


if __name__ == "__main__":
    unittest.main()
