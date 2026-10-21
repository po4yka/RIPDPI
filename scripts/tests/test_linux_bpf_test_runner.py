"""Fail-closed discovery tests for the capability-scoped Linux BPF lane."""
from __future__ import annotations

import importlib.util
import json
import tempfile
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


    def test_network_suite_selects_only_actual_filter_round_trip(self):
        name = "drop_sack_and_strip_timestamps_round_trip_succeeds"
        self.assertEqual([name], runner.select_tests(self.listing({name, "http_round_trip"}), "network-e2e"))

    def test_new_network_filter_test_requires_explicit_coverage(self):
        names = {"drop_sack_and_strip_timestamps_round_trip_succeeds", "strip_timestamps_only_round_trip"}
        with self.assertRaisesRegex(ValueError, "inventory changed"):
            runner.select_tests(self.listing(names), "network-e2e")

    def test_missing_network_filter_test_fails(self):
        with self.assertRaisesRegex(ValueError, "inventory changed"):
            runner.select_tests(self.listing({"http_round_trip"}), "network-e2e")

    def test_network_suite_rejects_unit_artifact_and_selects_integration_target(self):
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / "network_e2e"
            binary.write_text("test binary")
            binary.chmod(0o700)
            unit = {"reason": "compiler-artifact", "target": {"name": "ripdpi_privileged_ops", "kind": ["lib"]},
                    "profile": {"test": True}, "executable": str(binary)}
            integration = {"reason": "compiler-artifact", "target": {"name": "network_e2e", "kind": ["test"]},
                           "profile": {"test": True}, "executable": str(binary)}
            with self.assertRaisesRegex(ValueError, "expected one"):
                runner.select_binary(json.dumps(unit), "network-e2e")
            self.assertEqual(binary.resolve(), runner.select_binary(json.dumps(unit) + "\n" + json.dumps(integration), "network-e2e"))


if __name__ == "__main__":
    unittest.main()
