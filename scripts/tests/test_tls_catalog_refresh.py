from __future__ import annotations

import importlib.util
import json
import unittest
from datetime import date
from pathlib import Path


def load_module(module_name: str, relative_path: str):
    root = Path(__file__).resolve().parents[2]
    module_path = root / relative_path
    spec = importlib.util.spec_from_file_location(module_name, module_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


tls_catalog_refresh = load_module("tls_catalog_refresh", "scripts/ci/check_tls_catalog_refresh.py")


class TlsCatalogRefreshTest(unittest.TestCase):
    def test_repo_log_matches_catalog(self) -> None:
        summary = tls_catalog_refresh.validate_refresh_log(
            tls_catalog_refresh.load_json(tls_catalog_refresh.REFRESH_LOG_PATH),
            tls_catalog_refresh.load_json(tls_catalog_refresh.CATALOG_PATH),
            today=date(2026, 4, 18),
        )
        self.assertEqual("2026-04-18", summary["latestReviewedAt"])
        self.assertEqual(["browser_family_v2", "ech_canary_v1"], summary["profileSetIds"])

    def test_stale_review_is_rejected(self) -> None:
        catalog = {
            "tlsProfiles": [
                {"id": "browser_family_v2", "catalogVersion": "v1"},
            ]
        }
        log = {
            "version": "tls_catalog_refresh_log_v1",
            "cadenceDays": 30,
            "entries": [
                {
                    "reviewedAt": "2026-01-01",
                    "profileSets": [{"id": "browser_family_v2", "catalogVersion": "v1"}],
                }
            ],
        }
        with self.assertRaisesRegex(ValueError, "stale"):
            tls_catalog_refresh.validate_refresh_log(log, catalog, today=date(2026, 4, 18))

    def test_catalog_version_drift_is_rejected(self) -> None:
        catalog = {
            "tlsProfiles": [
                {"id": "browser_family_v2", "catalogVersion": "v2"},
            ]
        }
        log = {
            "version": "tls_catalog_refresh_log_v1",
            "cadenceDays": 30,
            "entries": [
                {
                    "reviewedAt": "2026-04-18",
                    "profileSets": [{"id": "browser_family_v2", "catalogVersion": "v1"}],
                }
            ],
        }
        with self.assertRaisesRegex(ValueError, "catalogVersion drift"):
            tls_catalog_refresh.validate_refresh_log(log, catalog, today=date(2026, 4, 18))


if __name__ == "__main__":
    unittest.main()
