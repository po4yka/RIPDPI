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
            tls_catalog_refresh.load_json(tls_catalog_refresh.ACCEPTANCE_CORPUS_PATH),
            tls_catalog_refresh.load_json(tls_catalog_refresh.ACCEPTANCE_REPORT_PATH),
            today=date(2026, 4, 18),
        )
        self.assertEqual("2026-04-18", summary["latestReviewedAt"])
        self.assertEqual(["browser_family_v2", "ech_canary_v1"], summary["profileSetIds"])
        self.assertEqual("phase11_tls_template_acceptance", summary["acceptanceCorpusRef"])
        self.assertEqual("tls_template_acceptance_report", summary["acceptanceReportRef"])

    def test_stale_review_is_rejected(self) -> None:
        catalog = {
            "tlsProfiles": [
                {
                    "id": "browser_family_v2",
                    "catalogVersion": "v1",
                    "acceptanceCorpusRef": "phase11_tls_template_acceptance",
                    "acceptanceReportRef": "tls_template_acceptance_report",
                    "reviewedAt": "2026-01-01",
                    "allowedProfileIds": ["chrome_stable"],
                },
            ]
        }
        log = {
            "version": "tls_catalog_refresh_log_v1",
            "cadenceDays": 30,
            "entries": [
                {
                    "reviewedAt": "2026-01-01",
                    "profileSets": [
                        {
                            "id": "browser_family_v2",
                            "catalogVersion": "v1",
                            "acceptanceCorpusRef": "phase11_tls_template_acceptance",
                            "acceptanceReportRef": "tls_template_acceptance_report",
                            "reviewedAt": "2026-01-01",
                            "allowedProfileIds": ["chrome_stable"],
                        }
                    ],
                }
            ],
        }
        with self.assertRaisesRegex(ValueError, "stale"):
            tls_catalog_refresh.validate_refresh_log(
                log,
                catalog,
                minimal_corpus(["chrome_stable"]),
                minimal_report(["chrome_stable"]),
                today=date(2026, 4, 18),
            )

    def test_catalog_version_drift_is_rejected(self) -> None:
        catalog = {
            "tlsProfiles": [
                {
                    "id": "browser_family_v2",
                    "catalogVersion": "v2",
                    "acceptanceCorpusRef": "phase11_tls_template_acceptance",
                    "acceptanceReportRef": "tls_template_acceptance_report",
                    "reviewedAt": "2026-04-18",
                    "allowedProfileIds": ["chrome_stable"],
                },
            ]
        }
        log = {
            "version": "tls_catalog_refresh_log_v1",
            "cadenceDays": 30,
            "entries": [
                {
                    "reviewedAt": "2026-04-18",
                    "profileSets": [
                        {
                            "id": "browser_family_v2",
                            "catalogVersion": "v1",
                            "acceptanceCorpusRef": "phase11_tls_template_acceptance",
                            "acceptanceReportRef": "tls_template_acceptance_report",
                            "reviewedAt": "2026-04-18",
                            "allowedProfileIds": ["chrome_stable"],
                        }
                    ],
                }
            ],
        }
        with self.assertRaisesRegex(ValueError, "catalogVersion drift"):
            tls_catalog_refresh.validate_refresh_log(
                log,
                catalog,
                minimal_corpus(["chrome_stable"]),
                minimal_report(["chrome_stable"]),
                today=date(2026, 4, 18),
            )

    def test_allowed_profile_drift_is_rejected(self) -> None:
        catalog = {
            "tlsProfiles": [
                {
                    "id": "browser_family_v2",
                    "catalogVersion": "v1",
                    "acceptanceCorpusRef": "phase11_tls_template_acceptance",
                    "acceptanceReportRef": "tls_template_acceptance_report",
                    "reviewedAt": "2026-04-18",
                    "allowedProfileIds": ["chrome_stable", "firefox_stable"],
                },
            ]
        }
        log = {
            "version": "tls_catalog_refresh_log_v1",
            "cadenceDays": 30,
            "entries": [
                {
                    "reviewedAt": "2026-04-18",
                    "profileSets": [
                        {
                            "id": "browser_family_v2",
                            "catalogVersion": "v1",
                            "acceptanceCorpusRef": "phase11_tls_template_acceptance",
                            "acceptanceReportRef": "tls_template_acceptance_report",
                            "reviewedAt": "2026-04-18",
                            "allowedProfileIds": ["chrome_stable"],
                        }
                    ],
                }
            ],
        }
        with self.assertRaisesRegex(ValueError, "allowedProfileIds drift"):
            tls_catalog_refresh.validate_refresh_log(
                log,
                catalog,
                minimal_corpus(["chrome_stable", "firefox_stable"]),
                minimal_report(["chrome_stable", "firefox_stable"]),
                today=date(2026, 4, 18),
            )


def minimal_corpus(profile_ids: list[str]) -> dict:
    profiles = []
    if "chrome_stable" in profile_ids:
        profiles.append(
            {
                "id": "chrome_stable",
                "echCapable": False,
                "acceptedStacks": ["cloudflare", "nginx_openssl"],
                "acceptanceResults": [
                    {"stackId": "cloudflare", "status": "accepted"},
                    {"stackId": "nginx_openssl", "status": "accepted"},
                ],
                "acceptanceSummary": {
                    "acceptedCdnStacks": 1,
                    "acceptedServerStacks": 1,
                    "acceptedEchStacks": 0,
                    "acceptedTotalStacks": 2,
                },
            }
        )
    if "firefox_stable" in profile_ids:
        profiles.append(
            {
                "id": "firefox_stable",
                "echCapable": False,
                "acceptedStacks": ["cloudflare", "envoy_boringssl"],
                "acceptanceResults": [
                    {"stackId": "cloudflare", "status": "accepted"},
                    {"stackId": "envoy_boringssl", "status": "accepted"},
                ],
                "acceptanceSummary": {
                    "acceptedCdnStacks": 1,
                    "acceptedServerStacks": 1,
                    "acceptedEchStacks": 0,
                    "acceptedTotalStacks": 2,
                },
            }
        )
    return {
        "schemaVersion": 2,
        "corpusId": "phase11_tls_template_acceptance",
        "coverageTargets": {
            "minimumAcceptedCdnStacks": 1,
            "minimumAcceptedServerStacks": 1,
            "minimumAcceptedStacksPerProfile": 2,
            "minimumAcceptedEchStacks": 1,
        },
        "stacks": [
            {"id": "cloudflare", "class": "cdn", "echCapable": True},
            {"id": "nginx_openssl", "class": "server", "echCapable": False},
            {"id": "envoy_boringssl", "class": "server", "echCapable": True},
        ],
        "profiles": profiles,
    }


def minimal_report(profile_ids: list[str]) -> dict:
    profiles = []
    server_stacks = set()
    if "chrome_stable" in profile_ids:
        profiles.append(
            {
                "id": "chrome_stable",
                "acceptedStacks": ["cloudflare", "nginx_openssl"],
                "acceptedCdnStacks": 1,
                "acceptedServerStacks": 1,
                "acceptedEchStacks": 0,
            }
        )
        server_stacks.add("nginx_openssl")
    if "firefox_stable" in profile_ids:
        profiles.append(
            {
                "id": "firefox_stable",
                "acceptedStacks": ["cloudflare", "envoy_boringssl"],
                "acceptedCdnStacks": 1,
                "acceptedServerStacks": 1,
                "acceptedEchStacks": 0,
            }
        )
        server_stacks.add("envoy_boringssl")
    return {
        "version": "tls_template_acceptance_report_v1",
        "corpusRef": "phase11_tls_template_acceptance",
        "profileSetIds": ["browser_family_v2"],
        "stackCoverage": {
            "cdnStacksCovered": ["cloudflare"],
            "serverStacksCovered": sorted(server_stacks),
            "echStacksCovered": [],
        },
        "profiles": profiles,
    }


if __name__ == "__main__":
    unittest.main()
