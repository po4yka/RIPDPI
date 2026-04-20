from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.analytics.bless import bless_candidate_artifacts
from scripts.analytics.cluster import run_cluster
from scripts.analytics.common import ROOT, load_json
from scripts.analytics.extract import run_extract
from scripts.analytics.publish import publish_outputs


SAMPLE_MANIFEST = ROOT / "scripts/analytics/sample-corpus.json"
SCHEMA_DIR = ROOT / "scripts/analytics/schemas"


def sample_inputs() -> list[Path]:
    manifest = load_json(SAMPLE_MANIFEST)
    return [(ROOT / relative_path).resolve() for relative_path in manifest["inputs"]]


class OfflineAnalyticsPipelineTest(unittest.TestCase):
    def test_extract_classifies_sample_corpus(self) -> None:
        extracted = run_extract(sample_inputs())

        self.assertEqual(6, extracted["recordCount"])
        by_id = {record["recordId"]: record for record in extracted["records"]}

        self.assertEqual(
            "resolver_interference",
            by_id["resolver-interference-a"]["connectivityAssessment"]["code"],
        )
        self.assertEqual(
            "resolver_interference",
            by_id["resolver-interference-b"]["connectivityAssessment"]["code"],
        )
        self.assertEqual(
            "service_runtime_failure",
            by_id["service-runtime-a"]["connectivityAssessment"]["code"],
        )
        self.assertIn(
            "trigger:httpfuzz:host_header_format",
            by_id["mixed-route-sensitive-a"]["observedFeatures"],
        )
        self.assertIn(
            "route_sensitive:true",
            by_id["mixed-route-sensitive-a"]["observedFeatures"],
        )

    def test_cluster_is_reproducible_for_sample_corpus(self) -> None:
        extracted = run_extract(sample_inputs())

        first = run_cluster(extracted)
        second = run_cluster(extracted)

        self.assertEqual(first["clusters"], second["clusters"])
        bucket_support = {cluster["bucket"]: cluster["supportCount"] for cluster in first["clusters"]}
        self.assertEqual(2, bucket_support["resolver_interference"])
        self.assertEqual(2, bucket_support["tls_split"])
        self.assertEqual(1, bucket_support["service_runtime"])
        self.assertEqual(1, bucket_support["route_sensitive"])

    def test_publish_emits_candidate_catalogs_and_report(self) -> None:
        extracted = run_extract(sample_inputs())
        clustered = run_cluster(extracted)

        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            outputs = publish_outputs(
                extracted_payload=extracted,
                clustered_payload=clustered,
                output_dir=temp_path,
                corpus_name="sample",
                blessed_device_catalog_path=temp_path / "missing-device.json",
                blessed_winner_mapping_path=temp_path / "missing-winner.json",
            )

            device_catalog = load_json(outputs["deviceCatalog"])
            winner_catalog = load_json(outputs["winnerCatalog"])
            drift_report = load_json(outputs["driftReport"])
            report_text = outputs["report"].read_text(encoding="utf-8")

        self.assertEqual("unreviewed", device_catalog["reviewStatus"])
        self.assertEqual("unreviewed", winner_catalog["reviewStatus"])
        self.assertEqual(0, drift_report["stableClusterCount"])
        self.assertIn("Offline Analytics Report", report_text)
        self.assertGreaterEqual(winner_catalog["mappingCount"], 2)

    def test_bless_promotes_candidates_to_reviewed_catalogs(self) -> None:
        extracted = run_extract(sample_inputs())
        clustered = run_cluster(extracted)

        with tempfile.TemporaryDirectory() as temp_dir:
            output_dir = Path(temp_dir) / "out"
            publish_outputs(
                extracted_payload=extracted,
                clustered_payload=clustered,
                output_dir=output_dir,
                corpus_name="sample",
            )
            blessed_dir = Path(temp_dir) / "blessed"
            result = bless_candidate_artifacts(
                device_catalog_candidate=output_dir / "device-fingerprint-catalog.candidate.json",
                winner_catalog_candidate=output_dir / "device-fingerprint-winner-mappings.candidate.json",
                blessed_device_catalog_path=blessed_dir / "device-fingerprint-catalog.json",
                blessed_winner_mapping_path=blessed_dir / "device-fingerprint-winner-mappings.json",
            )
            blessed_device_catalog = load_json(result["deviceCatalog"])
            blessed_winner_catalog = load_json(result["winnerCatalog"])

        self.assertEqual("reviewed", blessed_device_catalog["reviewStatus"])
        self.assertEqual("reviewed", blessed_winner_catalog["reviewStatus"])
        self.assertTrue(blessed_device_catalog["candidateSha256"])
        self.assertTrue(blessed_winner_catalog["candidateSha256"])

    def test_schema_files_exist_for_all_public_artifacts(self) -> None:
        expected = {
            "device-fingerprint-catalog-schema-v1.json",
            "device-fingerprint-winner-mappings-schema-v1.json",
            "offline-record-schema-v1.json",
        }
        actual = {path.name for path in SCHEMA_DIR.iterdir() if path.is_file()}
        self.assertTrue(expected.issubset(actual))

    def test_repo_blessed_assets_are_reviewed_catalogs(self) -> None:
        device_catalog = load_json(ROOT / "app/src/main/assets/offline-analytics/device-fingerprint-catalog.json")
        winner_catalog = load_json(ROOT / "app/src/main/assets/offline-analytics/device-fingerprint-winner-mappings.json")

        self.assertEqual("reviewed", device_catalog["reviewStatus"])
        self.assertEqual("reviewed", winner_catalog["reviewStatus"])
        self.assertGreaterEqual(device_catalog["clusterCount"], 4)
        self.assertGreaterEqual(winner_catalog["mappingCount"], 2)


if __name__ == "__main__":
    unittest.main()
