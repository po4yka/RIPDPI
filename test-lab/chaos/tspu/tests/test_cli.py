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


class CliLiveDispatchTests(unittest.TestCase):
    def test_live_subcommand_without_args_exits_nonzero(self):
        stderr = io.StringIO()
        original_stderr = sys.stderr
        sys.stderr = stderr
        try:
            rc = cli.main(["live"])
        finally:
            sys.stderr = original_stderr
        self.assertEqual(rc, 2)
        # Either the adapter-not-available branch or the missing-args
        # branch, depending on whether netfilterqueue is installed on
        # the host. Both mention "live mode" or "matrix" so the test
        # asserts on the documented contract surface.
        msg = stderr.getvalue().lower()
        self.assertTrue(
            "live mode" in msg or "matrix" in msg or "kerneladapter" in msg,
            f"unexpected stderr: {msg!r}",
        )

    def test_live_stub_message_contract_is_stable(self):
        # The LIVE_NOT_AVAILABLE_MESSAGE constant is the documented
        # contract that the v1.1 container's image-build status uses to
        # detect a missing live adapter. Pin the visible strings.
        self.assertIn("nfqueue", live.LIVE_NOT_AVAILABLE_MESSAGE.lower())
        self.assertIn("v1.1", live.LIVE_NOT_AVAILABLE_MESSAGE)


if __name__ == "__main__":
    unittest.main()
