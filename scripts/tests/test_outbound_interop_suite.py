"""Execution coverage for the upstream suite dispatcher, not protocol evidence."""

import importlib.util
from pathlib import Path
import tempfile
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


if __name__ == "__main__":
    unittest.main()
