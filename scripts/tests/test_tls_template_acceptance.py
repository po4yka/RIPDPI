from __future__ import annotations

import importlib.util
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


tls_template_acceptance = load_module(
    "tls_template_acceptance",
    "scripts/ci/check_tls_template_acceptance.py",
)


class TlsTemplateAcceptanceTest(unittest.TestCase):
    def test_repo_artifacts_match_thresholds(self) -> None:
        summary = tls_template_acceptance.validate_acceptance_artifacts(
            tls_template_acceptance.load_json(tls_template_acceptance.CATALOG_PATH),
            tls_template_acceptance.load_json(tls_template_acceptance.CORPUS_PATH),
            tls_template_acceptance.load_json(tls_template_acceptance.REPORT_PATH),
        )
        self.assertEqual("phase11_tls_template_acceptance", summary["corpusId"])
        self.assertEqual(
            ["browser_family_v2", "ech_canary_v1"],
            summary["profileSetIds"],
        )
        self.assertIn("cloudflare", summary["echStacksCovered"])
        self.assertIn("envoy_boringssl", summary["echStacksCovered"])

    def test_missing_server_threshold_is_rejected(self) -> None:
        catalog = {
            "tlsProfiles": [
                {
                    "id": "browser_family_v2",
                    "catalogVersion": "v1",
                    "acceptanceCorpusRef": "phase11_tls_template_acceptance",
                    "allowedProfileIds": ["chrome_stable"],
                },
            ],
        }
        corpus = {
            "schemaVersion": 2,
            "corpusId": "phase11_tls_template_acceptance",
            "coverageTargets": {
                "minimumAcceptedCdnStacks": 1,
                "minimumAcceptedServerStacks": 2,
                "minimumAcceptedStacksPerProfile": 2,
                "minimumAcceptedEchStacks": 1,
            },
            "stacks": [
                {"id": "cloudflare", "class": "cdn", "echCapable": True},
                {"id": "nginx_openssl", "class": "server", "echCapable": False},
            ],
            "profiles": [
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
                },
            ],
        }
        report = {
            "version": "tls_template_acceptance_report_v1",
            "corpusRef": "phase11_tls_template_acceptance",
            "profileSetIds": ["browser_family_v2"],
            "stackCoverage": {
                "cdnStacksCovered": ["cloudflare"],
                "serverStacksCovered": ["nginx_openssl"],
                "echStacksCovered": ["cloudflare"],
            },
            "profiles": [
                {
                    "id": "chrome_stable",
                    "acceptedStacks": ["cloudflare", "nginx_openssl"],
                    "acceptedCdnStacks": 1,
                    "acceptedServerStacks": 1,
                    "acceptedEchStacks": 1,
                },
            ],
        }
        with self.assertRaisesRegex(ValueError, "server threshold"):
            tls_template_acceptance.validate_acceptance_artifacts(catalog, corpus, report)


if __name__ == "__main__":
    unittest.main()
