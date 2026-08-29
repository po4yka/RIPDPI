import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "ci" / "run_target37_network_smoke.py"
SPEC = importlib.util.spec_from_file_location("target37_network_smoke", SCRIPT)
SMOKE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SMOKE)


class Target37NetworkSmokeTest(unittest.TestCase):
    def test_requires_direct_non_loopback_route_to_lan_peer(self):
        SMOKE.require_direct_lan_route(
            "172.20.10.6 dev wlan0 src 172.20.10.8 uid 10234\n",
            "172.20.10.6",
        )
        SMOKE.require_direct_lan_route(
            "fd12:3456::6 dev wlan0 src fd12:3456::8 metric 1024\n",
            "fd12:3456::6",
        )
        for route in [
            "172.20.10.6 via 10.0.2.2 dev eth0 src 10.0.2.15\n",
            "172.20.10.6 dev lo src 127.0.0.1\n",
            "default via 10.0.2.2 dev eth0\n",
            "",
        ]:
            with self.subTest(route=route), self.assertRaises(RuntimeError):
                SMOKE.require_direct_lan_route(route, "172.20.10.6")

    def test_requires_exactly_one_observed_successful_test(self):
        output = (
            f"INSTRUMENTATION_STATUS: class={SMOKE.TEST_CLASS}\n"
            f"INSTRUMENTATION_STATUS: test={SMOKE.TEST_METHOD}\n"
            "INSTRUMENTATION_STATUS_CODE: 1\n"
            "INSTRUMENTATION_STATUS_CODE: 0\n"
            "OK (1 test)\n"
        )
        SMOKE.require_test_success(output)
        for invalid in [
            "", "OK (0 tests)\n",
            output.replace("STATUS_CODE: 0", "STATUS_CODE: -3"),
            output.replace("STATUS_CODE: 0", "STATUS_CODE: -2"),
            output.replace(SMOKE.TEST_METHOD, "unrelated"),
            output + "INSTRUMENTATION_STATUS_CODE: 0\n",
        ]:
            with self.subTest(output=invalid), self.assertRaises(RuntimeError):
                SMOKE.require_test_success(invalid)


if __name__ == "__main__":
    unittest.main()
