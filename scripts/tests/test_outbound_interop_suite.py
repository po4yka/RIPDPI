"""Execution coverage for the upstream suite dispatcher, not protocol evidence."""

import importlib.util
from pathlib import Path
import tempfile
import runpy
import sys
import unittest


SCRIPT = Path(__file__).parents[1] / "ci/run-outbound-interop.py"


class OutboundSuiteTest(unittest.TestCase):
    def test_dispatches_every_pinned_case_and_stops_on_first_failure(self):
        spec = importlib.util.spec_from_file_location("outbound_suite", SCRIPT)
        suite = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(suite)
        calls = []

        def execute(command, cwd, env, **kwargs):
            calls.append(command)
            if command[0] != "git":
                raise RuntimeError("case must run in-process so its finally owns child cleanup")

        def run_case(arguments):
            calls.append(arguments)
            if arguments[-1] == "broken":
                raise RuntimeError("case failed")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runner = {"TESTS": {"ssh": ("first", "broken", "never")},
                      "REVISIONS": {"ssh": "a" * 40}, "checked": execute, "run": run_case}
            with self.assertRaisesRegex(RuntimeError, "case failed"):
                suite.run_cases(runner, root, {})
        cases = [command[-1] for command in calls if "--test" in command]
        self.assertEqual(["first", "broken"], cases)
        self.assertTrue(any("a" * 40 in command for command in calls))

    def test_vless_dispatch_uses_its_pinned_xray_repository(self):
        spec = importlib.util.spec_from_file_location("outbound_suite", SCRIPT)
        suite = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(suite)
        runner = runpy.run_path(str(SCRIPT.parents[1] / "tests/run-outbound-interop.py"))
        calls = []
        cases = []
        runner = {"TESTS": {"vless": runner["TESTS"]["vless"]},
                  "REVISIONS": {"vless": runner["REVISIONS"]["vless"]},
                  "checked": lambda command, *args, **kwargs: calls.append(command),
                  "run": cases.append}
        with tempfile.TemporaryDirectory() as directory:
            suite.run_cases(runner, Path(directory), {})
        self.assertEqual(len(runner["TESTS"]["vless"]), len(cases))
        self.assertTrue(any("https://github.com/XTLS/Xray-core.git" in call for call in calls))
        self.assertTrue(any(runner["REVISIONS"]["vless"] in call for call in calls))
        self.assertEqual(list(runner["TESTS"]["vless"]), [case[-1] for case in cases])

    def test_zero_or_ignored_selected_test_does_not_pass(self):
        runner = runpy.run_path(str(SCRIPT.parents[1] / "tests/run-outbound-interop.py"))
        for summary in ("0 passed; 0 failed; 0 ignored", "0 passed; 0 failed; 1 ignored"):
            with self.subTest(summary=summary), self.assertRaisesRegex(RuntimeError, "exactly one executed"):
                runner["checked"]([sys.executable, "-c", f"print({summary!r})"], SCRIPT.parent, {}, expected_test="selected")


if __name__ == "__main__":
    unittest.main()
