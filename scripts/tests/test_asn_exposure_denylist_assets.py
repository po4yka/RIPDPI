#!/usr/bin/env python3
"""Unit tests for scripts/ci/check_asn_exposure_denylist_assets.py."""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.ci import check_asn_exposure_denylist_assets as guard


class AsnExposureDenylistAssetsTest(unittest.TestCase):
    def test_current_repo_assets_pass(self) -> None:
        self.assertEqual([], guard.validate_no_asn_exposure_denylist_assets())

    def test_allowed_existing_asn_metadata_asset_passes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            asset = root / "core/data/settings/src/main/assets/integrations/asn-routing-map.json"
            asset.parent.mkdir(parents=True)
            asset.write_text('{"entries":[{"asn":13238,"label":"fixture"}]}', encoding="utf-8")

            self.assertEqual([], guard.validate_no_asn_exposure_denylist_assets(root))

    def test_public_source_provenance_in_runtime_asset_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            asset = root / "app/src/main/assets/offline-analytics/source.txt"
            asset.parent.mkdir(parents=True)
            asset.write_text("https://github.com/C24Be/AS_Network_List", encoding="utf-8")

            violations = guard.validate_no_asn_exposure_denylist_assets(root)

            self.assertEqual(1, len(violations))
            self.assertIn("source provenance", violations[0])

    def test_deploy_style_asn_denylist_asset_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            asset = root / "core/diagnostics/src/main/assets/advisory/asn-denylist.json"
            asset.parent.mkdir(parents=True)
            asset.write_text('{"ranges":["198.51.100.0/24"],"asn":["AS64500"]}', encoding="utf-8")

            violations = guard.validate_no_asn_exposure_denylist_assets(root)

            self.assertGreaterEqual(len(violations), 1)
            self.assertTrue(any("denylist" in violation for violation in violations))

    def test_neutrally_named_asset_with_asn_and_cidr_data_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            asset = root / "core/service/src/main/assets/integrations/network-policy.json"
            asset.parent.mkdir(parents=True)
            asset.write_text('{"asn":64500,"cidr":"203.0.113.0/24"}', encoding="utf-8")

            violations = guard.validate_no_asn_exposure_denylist_assets(root)

            self.assertEqual(1, len(violations))
            self.assertIn("combines ASN identifiers with IP ranges", violations[0])


if __name__ == "__main__":
    unittest.main()
