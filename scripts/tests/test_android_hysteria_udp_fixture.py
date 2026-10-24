"""Validate the owned UDP destination mapper before fixture mutation."""

import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ci"))
from android_hysteria_fixture import HysteriaFixture, owned_dns_outbound


class HysteriaDNSFixtureTests(unittest.TestCase):
    def test_exact_loopback_socks_outbound(self):
        self.assertEqual(
            {
                "name": "owned_dns",
                "type": "socks5",
                "socks5": {"addr": "127.0.0.1:12345"},
            },
            owned_dns_outbound({"dnsPort": 23456, "hysteriaSocksPort": 12345}),
        )

    def test_invalid_mapper_ports_fail_before_startup(self):
        for field in ("dnsPort", "hysteriaSocksPort"):
            for invalid in (None, True, False, 0, -1, 65536, "12345", 1.5):
                manifest = {
                    "dnsPort": 23456,
                    "hysteriaSocksPort": 12345,
                    field: invalid,
                }
                with self.subTest(field=field, invalid=invalid), patch(
                    "android_hysteria_fixture.verify_binary"
                ) as verify:
                    fixture = HysteriaFixture(
                        Path("unused"),
                        Path("unused"),
                        Path("unused"),
                        "127.0.0.1",
                        "127.0.0.1",
                        manifest,
                        1,
                    )
                    with self.assertRaises(ValueError):
                        fixture.start()
                    verify.assert_not_called()
