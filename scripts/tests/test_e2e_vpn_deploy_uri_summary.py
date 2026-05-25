import importlib.util
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / "scripts" / "e2e_vpn_deploy_uri_summary.py"
SCRIPT = ROOT / "scripts" / "e2e-vpn-deploy.sh"


spec = importlib.util.spec_from_file_location("e2e_vpn_deploy_uri_summary", HELPER)
uri_summary = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(uri_summary)


class E2EVpnDeployUriSummaryTest(unittest.TestCase):
    def test_summary_keeps_routing_hint_and_hash_only(self) -> None:
        uri = (
            "vless://11111111-2222-3333-4444-555555555555@example.com:31443"
            "?security=reality&pbk=public-key-secret&sid=short-secret#profile-name"
        )

        summary = uri_summary.summarize_uri(uri)

        self.assertTrue(summary.startswith("vless://example.com:31443#sha256="))
        self.assertNotIn("11111111-2222-3333-4444-555555555555", summary)
        self.assertNotIn("public-key-secret", summary)
        self.assertNotIn("short-secret", summary)
        self.assertNotIn("profile-name", summary)

    def test_summary_brackets_ipv6_hosts(self) -> None:
        uri = "vless://user@[2001:db8::1]:443?pbk=secret&sid=secret"

        summary = uri_summary.summarize_uri(uri)

        self.assertTrue(summary.startswith("vless://[2001:db8::1]:443#sha256="))
        self.assertNotIn("secret", summary)

    def test_e2e_script_logs_only_redacted_summary(self) -> None:
        script = SCRIPT.read_text(encoding="utf-8")

        self.assertIn("uri_summary=", script)
        self.assertIn("constructed URI: $uri_summary", script)
        self.assertIn("am start failed for VIEW $uri_summary", script)
        self.assertNotIn("constructed URI: ${uri:", script)
        self.assertNotIn('am start failed for VIEW $uri"', script)


if __name__ == "__main__":
    unittest.main()
