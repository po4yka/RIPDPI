from __future__ import annotations

import copy
import json
import tempfile
import unittest
from pathlib import Path

from scripts.analytics.cluster import run_cluster
from scripts.analytics.common import load_json
from scripts.analytics.publish import publish_outputs
from scripts.analytics.simulate import scenario_ids, simulate_corpus


def _strip_timestamps(payload: dict[str, object]) -> dict[str, object]:
    """Remove non-identifying generatedAt timestamps so content can be compared."""
    clone = copy.deepcopy(payload)
    clone.pop("generatedAt", None)
    for record in clone.get("records", []):  # type: ignore[union-attr]
        record.pop("generatedAt", None)
    return clone


class OfflineStrategySimulatorTest(unittest.TestCase):
    def test_simulate_corpus_is_deterministic_for_same_seed(self) -> None:
        ids = scenario_ids()

        first = simulate_corpus(ids, seed=7, count=4)
        second = simulate_corpus(ids, seed=7, count=4)

        self.assertEqual(_strip_timestamps(first), _strip_timestamps(second))

    def test_simulate_corpus_differs_for_different_seed(self) -> None:
        ids = scenario_ids()

        first = simulate_corpus(ids, seed=7, count=4)
        other = simulate_corpus(ids, seed=8, count=4)

        self.assertNotEqual(_strip_timestamps(first), _strip_timestamps(other))

    def test_record_count_equals_scenarios_times_count(self) -> None:
        ids = ["sni_block", "dns_poison", "tls_split"]
        count = 5

        payload = simulate_corpus(ids, seed=3, count=count)

        self.assertEqual(len(payload["records"]), payload["recordCount"])
        self.assertEqual(payload["recordCount"], len(ids) * count)

    def test_record_ids_are_deterministic_from_scenario_seed_index(self) -> None:
        payload = simulate_corpus(["sni_block"], seed=42, count=2)

        record_ids = [record["recordId"] for record in payload["records"]]
        self.assertEqual(record_ids, ["sim-sni_block-42-0", "sim-sni_block-42-1"])

    def test_simulated_payload_flows_through_cluster_and_publish(self) -> None:
        payload = simulate_corpus(scenario_ids(), seed=1337, count=4)
        clustered = run_cluster(payload)

        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            outputs = publish_outputs(
                extracted_payload=payload,
                clustered_payload=clustered,
                output_dir=temp_path,
                corpus_name="simulated",
                blessed_device_catalog_path=temp_path / "missing-device.json",
                blessed_winner_mapping_path=temp_path / "missing-winner.json",
            )
            strategy_pack_catalog = load_json(outputs["strategyPackCatalog"])

        generated_packs = [
            pack for pack in strategy_pack_catalog["packs"] if pack["id"].startswith("offline-")
        ]
        self.assertGreater(len(generated_packs), 0)
        for pack in generated_packs:
            self.assertTrue(pack["rollout"]["staged"])
            self.assertEqual(0, pack["rollout"]["percentage"])

    def test_simulated_payload_contains_no_real_network_identifiers(self) -> None:
        payload = simulate_corpus(scenario_ids(), seed=99, count=4)
        serialized = json.dumps(payload).lower()

        for forbidden in ("bssid", "imei", "imsi", "ssid"):
            self.assertNotIn(forbidden, serialized)

        target_labels = [
            target["label"]
            for record in payload["records"]
            for target in record["affectedTargets"]
        ]
        self.assertTrue(target_labels)
        for label in target_labels:
            self.assertRegex(label, r"^sim-target-\d+\.example$")
            self.assertNotIn("ssid", label.lower())


if __name__ == "__main__":
    unittest.main()
