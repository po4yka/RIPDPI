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

    def test_nested_checkout_assets_are_not_scanned(self) -> None:
        # Assets inside `.claude/worktrees/*` belong to a nested checkout, so their
        # paths never match the allowlist and every allowlisted file was reported.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            asset = (
                root
                / ".claude/worktrees/job/core/service/src/main/assets/integrations/network-policy.json"
            )
            asset.parent.mkdir(parents=True)
            asset.write_text('{"asn":64500,"cidr":"203.0.113.0/24"}', encoding="utf-8")

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

    def test_asn_json_key_forms_are_detected(self) -> None:
        # This alternative sat behind a word boundary that can never match before
        # a quote, so every `"asn": <number>` asset slipped through unreported.
        for payload in (
            '{"asn":64500}',
            '{"asn": 64500}',
            '{"asn":"64500"}',
            '{"asn":[64500,64501]}',
            '{"ASN": 64500}',
        ):
            with self.subTest(payload=payload):
                self.assertIsNotNone(guard.ASN_RE.search(payload))

    def test_asn_pattern_ignores_short_or_absent_identifiers(self) -> None:
        for payload in ('{"asn":"n/a"}', '{"assignee":64500}', '{"region":"eu"}'):
            with self.subTest(payload=payload):
                self.assertIsNone(guard.ASN_RE.search(payload))

    def test_cidr_matches_keep_the_full_prefix_length(self) -> None:
        # Ordered alternation used to stop at the first digit, reporting `/2` for
        # a `/24` range. The count was unaffected, the reported range was not.
        self.assertEqual(["203.0.113.0/24"], guard.CIDR_RE.findall('"203.0.113.0/24"'))
        self.assertEqual(["10.0.0.0/8"], guard.CIDR_RE.findall('"10.0.0.0/8"'))
        self.assertEqual(["1.2.3.4/32"], guard.CIDR_RE.findall('"1.2.3.4/32"'))
        self.assertEqual(
            ["2001:0db8:0000:0000:0000:0000:0000:0001/32"],
            guard.CIDR_RE.findall('"2001:0db8:0000:0000:0000:0000:0000:0001/32"'),
        )

    def test_compressed_ipv6_cidr_is_a_known_blind_spot(self) -> None:
        # Documents a pre-existing gap rather than endorsing it: `::` compression
        # is not recognised, so an IPv6-only denylist using it would pass. Delete
        # this test when the pattern learns compressed notation.
        self.assertEqual([], guard.CIDR_RE.findall('"2001:db8::/32"'))


if __name__ == "__main__":
    unittest.main()
