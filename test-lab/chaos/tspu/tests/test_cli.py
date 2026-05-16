"""CLI entry-point tests."""

from __future__ import annotations

import io
import os
import sys
import unittest


HERE = os.path.dirname(os.path.abspath(__file__))
TSPU_DIR = os.path.dirname(HERE)
if TSPU_DIR not in sys.path:
    sys.path.insert(0, TSPU_DIR)


from runner import cli, live  # noqa: E402


class CliLiveStubTests(unittest.TestCase):
    def test_live_subcommand_exits_nonzero_with_documented_message(self):
        stderr = io.StringIO()
        original_stderr = sys.stderr
        sys.stderr = stderr
        try:
            rc = cli.main(["live"])
        finally:
            sys.stderr = original_stderr
        self.assertEqual(rc, 2)
        self.assertIn("not implemented", stderr.getvalue().lower())

    def test_live_stub_message_contract_is_stable(self):
        self.assertIn("nfqueue", live.LIVE_NOT_IMPLEMENTED_MESSAGE.lower())
        self.assertIn("v1.1", live.LIVE_NOT_IMPLEMENTED_MESSAGE)


if __name__ == "__main__":
    unittest.main()
