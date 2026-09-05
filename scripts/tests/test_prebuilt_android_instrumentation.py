import json
import shlex
import subprocess
import tempfile
import unittest
from argparse import Namespace
from pathlib import Path
from unittest.mock import patch

from scripts.ci.prebuilt_android_instrumentation import VARIANTS, orchestrator_command, parse_results, run, seal, verify
from scripts.ci.validate_android_junit_results import validate


def test_output(code=0, final="INSTRUMENTATION_CODE: -1\n"):
    return f"""INSTRUMENTATION_STATUS: class=example.Test
INSTRUMENTATION_STATUS: test=works
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=example.Test
INSTRUMENTATION_STATUS: test=works
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: stack=trace start
  trace continuation
INSTRUMENTATION_STATUS_CODE: {code}
{final}"""


class PrebuiltInstrumentationTest(unittest.TestCase):
    def test_completed_test_produces_junit_evidence(self):
        output = """INSTRUMENTATION_STATUS: class=example.Test
INSTRUMENTATION_STATUS: test=works
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=example.Test
INSTRUMENTATION_STATUS: test=works
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_CODE: -1
"""
        suite = parse_results(output)
        self.assertEqual("1", suite.get("tests"))
        self.assertEqual("0", suite.get("failures"))
        self.assertEqual("example.Test", suite[0].get("classname"))
        self.assertEqual("works", suite[0].get("name"))

    def test_failures_errors_and_assumptions_remain_visible(self):
        for code, tag, aggregate in ((-1, "error", "errors"), (-2, "failure", "failures"),
                                     (-3, "skipped", "skipped"), (-4, "skipped", "skipped")):
            with self.subTest(code=code):
                suite = parse_results(test_output(code))
                self.assertEqual("1", suite.get(aggregate))
                self.assertIn("trace continuation", suite[0].find(tag).text)

    def test_rejects_incomplete_or_ambiguous_protocol(self):
        output = test_output()
        for broken in (
            "", test_output(final=""), test_output(final="INSTRUMENTATION_CODE: 0\n"),
            output.replace("numtests=1", "numtests=2"),
            output.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: 1"),
            output.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: 99"),
            test_output(final="INSTRUMENTATION_RESULT: shortMsg=Process crashed\nINSTRUMENTATION_CODE: -1\n"),
            output + output,
        ):
            with self.subTest(output=broken):
                with self.assertRaises(ValueError):
                    parse_results(broken)

    def test_orchestrator_preserves_isolation_and_quotes_filter_values(self):
        target = "com.poyka.ripdpi.test/com.poyka.ripdpi.HiltTestRunner"
        value = "example.Test#method; touch /tmp/unexpected"
        command = orchestrator_command(target, {"class": value, "ripdpi.xrayFixturePort": "1234"})
        self.assertTrue(command.startswith("CLASSPATH=$(pm path androidx.test.services) app_process / "))
        tokens = shlex.split(command.split("ShellMain ", 1)[1])
        self.assertIn(value, tokens)
        self.assertIn(target, tokens)
        self.assertIn(["-e", "clearPackageData", "true"], [tokens[i:i + 3] for i in range(len(tokens))])
        self.assertEqual("androidx.test.orchestrator/.AndroidTestOrchestrator", tokens[-1])

    def test_bundle_binds_both_apk_pairs_and_utilities_to_source_sha(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for variant, package in VARIANTS.items():
                for kind in ("app", "test"):
                    directory = root / variant / kind
                    directory.mkdir(parents=True)
                    (directory / "test.apk").write_bytes(b"checksum input " + variant.encode() + kind.encode())
                    (directory / "output-metadata.json").write_text(json.dumps({
                        "applicationId": package + (".test" if kind == "test" else ""),
                        "elements": [{"outputFile": "test.apk", "filters": []}],
                    }))
            (root / "utilities").mkdir()
            for name in ("orchestrator-1.6.1.apk", "test-services-1.6.0.apk"):
                (root / "utilities" / name).write_bytes(name.encode())
            seal(root, "a" * 40)
            manifest = verify(root, "a" * 40)
            self.assertEqual(6, len(manifest["files"]))
            with self.assertRaisesRegex(ValueError, "source SHA"):
                verify(root, "b" * 40)
            (root / "utilities/orchestrator-1.6.1.apk").write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "checksum"):
                verify(root, "a" * 40)
            (root / "utilities/orchestrator-1.6.1.apk").unlink()
            with self.assertRaisesRegex(ValueError, "Expected one"):
                seal(root, "a" * 40)

    def test_apk_bundle_rejects_files_outside_bundle(self):
        from scripts.ci.prebuilt_android_instrumentation import bundle_file
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "bundle"
            root.mkdir()
            outside = Path(temporary) / "outside.apk"
            outside.write_bytes(b"outside")
            (root / "link.apk").symlink_to(outside)
            for name in ("../outside.apk", str(outside), "link.apk", "missing.apk"):
                with self.subTest(name=name), self.assertRaises(ValueError):
                    bundle_file(root, name)

    def test_runner_installs_utilities_and_rejects_zero_exit_with_truncated_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in ("app.apk", "test.apk", "orchestrator.apk", "services.apk"):
                (root / name).write_bytes(b"install input")
            manifest = {"variants": {"githubFullDebug": {
                "package": "com.poyka.ripdpi", "app": "app.apk", "test": "test.apk",
            }}, "utilities": ["orchestrator.apk", "services.apk"]}
            calls = []
            protocol = test_output()

            def adb(command, **options):
                calls.append(command)
                output = ""
                if command[3:] == ["shell", "getprop", "ro.build.version.sdk"]:
                    output = "35\n"
                elif command[3:] == ["shell", "pm", "list", "instrumentation"]:
                    output = "instrumentation:com.poyka.ripdpi.test/com.poyka.ripdpi.HiltTestRunner (target=com.poyka.ripdpi)\n"
                elif "stdout" in options:
                    options["stdout"].write(protocol)
                return subprocess.CompletedProcess(command, 0, output, "")

            args = Namespace(bundle=root, sha="a" * 40, results=root / "results", api=35,
                             variant="githubFullDebug", test_class="example.Test", test_package=None, xray_port=1234)
            with patch("scripts.ci.prebuilt_android_instrumentation.verify", return_value=manifest), \
                 patch("subprocess.check_output", return_value="List of devices attached\nemulator-5554\tdevice\n"), \
                 patch("subprocess.run", side_effect=adb):
                run(args)
                self.assertEqual(0, validate(args.results, "example.Test#works", expected_count=1,
                                            expected_total_count=1, minimum_total_count=None, forbid_skips=True))
                installs = [call for call in calls if call[3] == "install"]
                self.assertEqual(4, len(installs))
                self.assertTrue(all("--force-queryable" in call for call in installs))
                self.assertEqual(4, sum(call[3] == "uninstall" for call in calls))
                protocol = test_output(final="")
                args.results = root / "truncated"
                with self.assertRaisesRegex(ValueError, "Incomplete"):
                    run(args)
                self.assertTrue((args.results / "instrumentation.txt").is_file())
                self.assertFalse((args.results / "TEST-instrumentation.xml").exists())
                self.assertEqual(8, sum(call[3] == "uninstall" for call in calls))
