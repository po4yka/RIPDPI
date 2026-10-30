from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from tls_capture_summary import HRR_RANDOM, summarize_rows

JA4 = "t13d1517h2_8daaf6152771_cb7bf5808d99"


def row(stream, when, types, ja4="", random="", groups=""):
    return "\t".join([stream, when, types, ja4, random, groups])


class SummaryTests(unittest.TestCase):
    def test_single_client_hello_without_retry(self):
        rows = "\n".join([row("0", "1.0", "1", JA4, "aa", "4588,29"), row("0", "1.1", "2", "", "bb")])
        result = summarize_rows(rows)
        self.assertEqual([JA4], result["ja4"])
        self.assertFalse(result["hello_retry_request"])
        self.assertEqual(1, result["connections"][0]["client_hellos"])

    def test_retry_random_marks_hello_retry_request(self):
        colon = ":".join(HRR_RANDOM[i : i + 2] for i in range(0, 64, 2))
        rows = "\n".join([row("3", "2.0", "1", JA4), row("3", "2.1", "2", "", colon)])
        self.assertTrue(summarize_rows(rows)["connections"][0]["hello_retry_request"])

    def test_second_client_hello_marks_retry_per_connection_only(self):
        rows = "\n".join([row("0", "1", "1", JA4), row("1", "2", "1", JA4), row("1", "3", "1", JA4)])
        flags = {c["stream"]: c["hello_retry_request"] for c in summarize_rows(rows)["connections"]}
        self.assertEqual({0: False, 1: True}, flags)


if __name__ == "__main__":
    unittest.main()
