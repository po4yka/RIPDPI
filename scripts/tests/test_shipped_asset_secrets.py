#!/usr/bin/env python3
"""Unit tests for scripts/ci/check_shipped_asset_secrets.py.

Every secret below is a synthetic marker, never a real key or password.
"""
from __future__ import annotations

import io
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path

from scripts.ci import check_shipped_asset_secrets as guard

FAKE = "synthetic-fixture-value"


class ShippedAssetSecretsTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def write(self, relative: str, text: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def violations(self) -> list[str]:
        return guard.validate_no_shipped_asset_secrets(self.root)

    def test_current_repo_assets_pass(self) -> None:
        self.assertEqual([], guard.validate_no_shipped_asset_secrets())

    def test_private_key_is_rejected_with_file_and_key_but_no_value(self) -> None:
        self.write(
            "app/src/main/assets/relay.json",
            f'{{"outbounds":[{{"type":"wireguard","private_key":"{FAKE}"}}]}}',
        )
        stderr = io.StringIO()
        with redirect_stderr(stderr):
            exit_code = guard.main(["--repo-root", str(self.root)])
        self.assertEqual(1, exit_code)
        self.assertIn("app/src/main/assets/relay.json", stderr.getvalue())
        self.assertIn("$.outbounds[0].private_key", stderr.getvalue())
        self.assertNotIn(FAKE, stderr.getvalue())

    def test_preset_password_under_main_assets_is_rejected(self) -> None:
        self.write(
            "app/src/main/assets/preset.json",
            f'{{"privateData":{{"relays":[{{"server":"relay.example","password":"{FAKE}"}}]}}}}',
        )
        self.assertEqual(
            ["app/src/main/assets/preset.json: shipped asset carries secret material at $.privateData.relays[0].password"],
            self.violations(),
        )

    def test_flavor_preshared_key_and_camel_case_names_are_rejected(self) -> None:
        self.write(
            "app/src/play/assets/bundle.json",
            f'{{"peer":{{"presharedKey":"{FAKE}","obfsPassword":"{FAKE}","psk":"{FAKE}"}}}}',
        )
        self.assertEqual(
            {"$.peer.presharedKey", "$.peer.obfsPassword", "$.peer.psk"},
            {v.rsplit(" at ", 1)[1] for v in self.violations()},
        )

    def test_renamed_file_is_still_scanned_by_content(self) -> None:
        self.write("core/service/src/main/assets/innocent-name.txt", f"[Interface]\nPrivateKey = {FAKE}\n")
        self.assertEqual(1, len(self.violations()))

    def test_pem_private_key_block_is_rejected(self) -> None:
        self.write(
            "app/src/github/assets/ssh/key",
            "-----BEGIN OPENSSH PRIVATE KEY-----\nsynthetic\n-----END OPENSSH PRIVATE KEY-----\n",
        )
        self.assertEqual(
            ["app/src/github/assets/ssh/key: shipped asset carries secret material at PEM private key block"],
            self.violations(),
        )

    def test_endpoint_metadata_and_empty_token_slot_pass(self) -> None:
        self.write(
            "app/src/main/assets/preset.json",
            '{"server":"relay.example","port":443,"public_key":"not-secret","bootstrap_token":"",'
            '"password":"","private_key":null}',
        )
        self.assertEqual([], self.violations())

    def test_test_source_sets_and_nested_checkouts_are_skipped(self) -> None:
        secret = f'{{"password":"{FAKE}"}}'
        self.write("app/src/androidTest/assets/fixture.json", secret)
        self.write("app/src/test/assets/fixture.json", secret)
        self.write(".worktrees/job/app/src/main/assets/preset.json", secret)
        self.write("app/build/intermediates/src/main/assets/preset.json", secret)
        self.assertEqual([], self.violations())

    def test_numeric_code_constant_named_like_a_key_passes(self) -> None:
        self.write("core/engine/src/main/assets/lua/lib.lua", "local TLS_EXT_PRE_SHARED_KEY = 41\n")
        self.assertEqual([], self.violations())


if __name__ == "__main__":
    unittest.main()
