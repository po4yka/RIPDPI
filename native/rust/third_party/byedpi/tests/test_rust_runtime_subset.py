#!/usr/bin/env python3

from __future__ import annotations

import argparse
import unittest

import test_proxy_integration as proxy_tests


SELECTED_TESTS = [
    "test_socks5_echo",
    "test_socks4_echo",
    "test_http_connect_echo",
    "test_socks5_tls_tunnel",
    "test_socks5_udp_associate_echo",
    "test_udp_fake_burst_reaches_server_before_payload",
    "test_connection_churn_echo",
    "test_no_domain_rejects_domain_requests",
    "test_no_udp_rejects_udp_associate",
    "test_connect_failure_does_not_yield_a_working_tunnel",
    "test_external_socks_chain",
    "test_socks5_ipv6_echo",
]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", required=True)
    args = parser.parse_args()

    proxy_tests.PROXY_BINARY = args.binary
    suite = unittest.TestSuite(
        proxy_tests.ProxyIntegrationTests(name) for name in SELECTED_TESTS
    )
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
