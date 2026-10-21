"""Fail-closed discovery tests for the capability-scoped Linux BPF lane."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest


PATH = Path(__file__).resolve().parents[1] / "ci/run-linux-bpf-tests.py"
spec = importlib.util.spec_from_file_location("linux_bpf_runner", PATH)
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class LinuxBpfTestRunnerTest(unittest.TestCase):
    def listing(self, tests):
        return "\n".join(f"{name}: test" for name in tests)

    def test_selects_all_three_socket_filters_and_no_other_tests(self):
        listing = self.listing(runner.EXPECTED_TESTS | {"linux::tests::socket_options::rcvbuf_set_and_readback"})
        self.assertEqual(sorted(runner.EXPECTED_TESTS), runner.select_tests(listing))

    def test_namespace_move_fails_instead_of_silently_running_zero_tests(self):
        moved = {name.replace("linux::tests", "platform::linux::tests") for name in runner.EXPECTED_TESTS}
        with self.assertRaisesRegex(ValueError, "inventory changed"):
            runner.select_tests(self.listing(moved))

    def test_missing_filter_fails(self):
        with self.assertRaisesRegex(ValueError, "inventory changed"):
            runner.select_tests(self.listing(sorted(runner.EXPECTED_TESTS)[1:]))

    def test_new_filter_requires_explicit_coverage(self):
        with self.assertRaisesRegex(ValueError, "inventory changed"):
            runner.select_tests(self.listing(runner.EXPECTED_TESTS | {"linux::tests::socket_options::bpf_new_filter"}))


if __name__ == "__main__":
    unittest.main()
