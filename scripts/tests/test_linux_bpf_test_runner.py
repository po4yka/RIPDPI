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


    def test_coverage_profile_is_forwarded_after_uid_and_capability_drop(self):
        profile = "/tmp/coverage with spaces/rust-%p-%14m.profraw"
        command = runner.runtime_command(Path("/tmp/test-binary"), "test", {
            "CARGO_LLVM_COV": "1", "LLVM_PROFILE_FILE": profile, "UNRELATED_SECRET": "not-forwarded",
        })
        index = command.index("--no-new-privs")
        self.assertEqual(["--", "env", f"LLVM_PROFILE_FILE={profile}", "/tmp/test-binary"], command[index + 1:index + 5])
        self.assertFalse(any("UNRELATED_SECRET" in arg for arg in command))

    def test_instrumented_execution_requires_a_profile_destination(self):
        with self.assertRaisesRegex(ValueError, "LLVM_PROFILE_FILE"):
            runner.runtime_command(Path("/tmp/test-binary"), "test", {"CARGO_LLVM_COV": "1"})

    def test_ordinary_execution_does_not_add_runtime_environment(self):
        command = runner.runtime_command(Path("/tmp/test-binary"), "test", {})
        self.assertNotIn("env", command)


if __name__ == "__main__":
    unittest.main()
