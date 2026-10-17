"""Synthetic parser tests only. These fixtures are not device performance evidence."""

import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "ci/check-compose-styles-performance.py"
SPEC = importlib.util.spec_from_file_location("compose_styles_performance", SCRIPT)
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


class ComposeStylesPerformanceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.manifest = {"runs": []}
        self.context = {
            "build": {
                "brand": "unit", "device": "unit", "fingerprint": "unit/not-device-evidence",
                "id": "unit", "model": "unit", "type": "user", "version": {"sdk": 35, "codename": "REL"},
            },
            "cpuCoreCount": 8, "cpuLocked": False, "cpuMaxFreqHz": 1000000000,
            "memTotalBytes": 1000000000, "sustainedPerformanceModeEnabled": False, "compilationMode": "speed",
        }
        for state_index, state in enumerate(("original", "dependencies", "styles"), 1):
            apk = self.root / f"{state}.apk"
            apk.write_bytes(f"unit fixture, not an Android APK: {state}".encode())
            for series in (1, 2):
                result = self.root / f"{state}-{series}.json"
                benchmarks = [self.benchmark(name) for name in (
                    "startupCold", "settingsScroll", "logsScroll", "controlInteractions",
                )]
                for index, benchmark in enumerate(benchmarks):
                    benchmark["totalRunTimeNs"] = state_index * 1000000 + series * 1000 + index
                result.write_text(json.dumps({"context": self.context, "benchmarks": benchmarks}))
                self.manifest["runs"].append({
                    "state": state, "series": series, "result_files": [result.name],
                    "device_serial": "unit-only-serial", "source_sha": str(state_index) * 40,
                    "apk_path": apk.name, "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                })
        self.manifest_path = self.root / "manifest.json"

    def benchmark(self, name):
        benchmark = {
            "name": name, "className": "com.poyka.ripdpi.performance.ComposeStylesBenchmark",
            "repeatIterations": 10, "metrics": {}, "sampledMetrics": {},
        }
        if name == "startupCold":
            benchmark["metrics"] = {"timeToInitialDisplayMs": {"median": 100, "runs": [100] * 10}}
        else:
            benchmark["sampledMetrics"] = {"frameDurationCpuMs": {"P95": 10, "runs": [[10] * 20 for _ in range(10)]}}
        return benchmark

    def result_path(self, state="styles", series=1):
        return self.root / f"{state}-{series}.json"

    def rewrite(self, change, state="styles", series=1):
        path = self.result_path(state, series)
        data = json.loads(path.read_text())
        change(data)
        path.write_text(json.dumps(data))

    def set_value(self, state, series, name, value):
        def change(data):
            benchmark = next(item for item in data["benchmarks"] if item["name"] == name)
            if name == "startupCold":
                benchmark["metrics"]["timeToInitialDisplayMs"] = {"median": value, "runs": [value] * 10}
            else:
                benchmark["sampledMetrics"]["frameDurationCpuMs"] = {"P95": value, "runs": [[value] * 20 for _ in range(10)]}
        self.rewrite(change, state, series)

    def validate(self):
        self.manifest_path.write_text(json.dumps(self.manifest))
        return CHECK.evaluate(self.manifest_path)

    def set_context_values(self, **values):
        for state in ("original", "dependencies", "styles"):
            for series in (1, 2):
                self.rewrite(lambda data: data["context"].update(self.context, **values), state, series)

    def test_complete_comparison_reports_all_pairs_and_metrics(self):
        report = self.validate()
        self.assertEqual("PASS", report["status"])
        self.assertEqual(24, len(report["comparisons"]))
        self.assertEqual(6, len(report["runs"]))
        self.assertEqual({"original->dependencies", "dependencies->styles", "original->styles"}, {
            row["comparison"] for row in report["comparisons"]
        })

    def test_percentile_uses_pooled_nested_frames_and_androidx_interpolation(self):
        def change(data):
            metric = data["benchmarks"][1]["sampledMetrics"]["frameDurationCpuMs"]
            metric["runs"] = [[1] * 10, [100]] + [[1] for _ in range(8)]
            metric["P95"] = 10.9
        self.rewrite(change)
        report = self.validate()
        run = next(run for run in report["runs"] if run["state"] == "styles" and run["series"] == 1)
        self.assertAlmostEqual(10.9, run["metrics"]["settingsScroll/frameDurationCpuMs:p95"])

    def test_repeated_final_regression_fails_and_exact_five_percent_passes(self):
        for series in (1, 2):
            self.set_value("styles", series, "startupCold", 105)
        self.assertEqual("PASS", self.validate()["status"])
        for series in (1, 2):
            self.set_value("styles", series, "startupCold", 106)
        report = self.validate()
        self.assertEqual("FAIL", report["status"])
        self.assertIn("startupCold/timeToInitialDisplayMs:median", report["reproducible_regressions"])

    def test_single_series_or_different_scenarios_are_inconclusive(self):
        self.set_value("styles", 1, "startupCold", 106)
        self.set_value("styles", 2, "logsScroll", 11)
        report = self.validate()
        self.assertEqual("INCONCLUSIVE", report["status"])
        self.assertEqual([], report["reproducible_regressions"])
        self.assertEqual(2, len(report["single_series_regressions"]))

    def test_intermediate_regression_is_visible_when_styles_recover(self):
        for series in (1, 2):
            self.set_value("dependencies", series, "settingsScroll", 12)
        report = self.validate()
        self.assertEqual("PASS", report["status"])
        rows = [row for row in report["comparisons"] if row["comparison"] == "original->dependencies"]
        self.assertEqual(2, sum(row["over_threshold"] for row in rows))

    def test_requires_exact_two_complete_series_and_unique_files(self):
        original = json.loads(json.dumps(self.manifest))
        for mutate in (
            lambda value: value["runs"].pop(),
            lambda value: value["runs"][0].update(series=3),
            lambda value: value["runs"][0].update(result_files=value["runs"][1]["result_files"]),
        ):
            self.manifest = json.loads(json.dumps(original))
            mutate(self.manifest)
            with self.assertRaises(ValueError):
                self.validate()

    def test_rejects_missing_wrong_class_duplicate_or_partial_benchmarks(self):
        original = self.result_path().read_text()
        mutations = (
            lambda data: data["benchmarks"].pop(),
            lambda data: data["benchmarks"][0].update(className="other.StartupBenchmark"),
            lambda data: data["benchmarks"].append(data["benchmarks"][0]),
            lambda data: data["benchmarks"][0].update(repeatIterations=9),
            lambda data: data["benchmarks"][1]["sampledMetrics"].clear(),
        )
        for mutate in mutations:
            self.result_path().write_text(original)
            self.rewrite(mutate)
            with self.assertRaises(ValueError):
                self.validate()

    def test_rejects_nonfinite_nonpositive_boolean_empty_and_inconsistent_metrics(self):
        original = self.result_path().read_text()
        for value in (float("nan"), float("inf"), -1, 0, True):
            self.result_path().write_text(original)
            self.set_value("styles", 1, "startupCold", value)
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.validate()
        self.result_path().write_text(original)
        self.rewrite(lambda data: data["benchmarks"][1]["sampledMetrics"]["frameDurationCpuMs"].update(runs=[[]] * 10))
        with self.assertRaises(ValueError):
            self.validate()
        self.result_path().write_text(original)
        self.rewrite(lambda data: data["benchmarks"][0]["metrics"]["timeToInitialDisplayMs"].update(median=999))
        with self.assertRaises(ValueError):
            self.validate()
        for value in (float("nan"), 0, True):
            self.result_path().write_text(original)
            self.set_value("styles", 1, "settingsScroll", value)
            with self.subTest(frame_value=value), self.assertRaises(ValueError):
                self.validate()

    def test_rejects_changed_device_or_context_and_missing_identity(self):
        original = self.result_path().read_text()
        for mutate in (
            lambda data: data["context"]["build"].update(fingerprint="different-device"),
            lambda data: data["context"].update(cpuLocked=True),
            lambda data: data["context"].pop("cpuMaxFreqHz"),
        ):
            self.result_path().write_text(original)
            self.rewrite(mutate)
            with self.assertRaises(ValueError):
                self.validate()
        self.result_path().write_text(original)
        self.manifest["runs"][0]["device_serial"] = "another-unit-device"
        with self.assertRaises(ValueError):
            self.validate()

    def test_source_backed_unknown_context_values_are_preserved_and_reported(self):
        for unknowns in (
            {"cpuMaxFreqHz": -1},
            {"memTotalBytes": -1, "cpuCoreCount": 0},
            {"cpuMaxFreqHz": -1, "memTotalBytes": -1, "cpuCoreCount": 0},
        ):
            self.set_context_values(**unknowns)
            with self.subTest(unknowns=unknowns):
                report = self.validate()
                self.assertEqual("PASS", report["status"])
                warnings = report["unknown_metadata_warnings"]
                self.assertEqual(len(unknowns), len(warnings))
                for key, value in unknowns.items():
                    self.assertTrue(any(key in warning for warning in warnings))
                    self.assertEqual({value}, {run["context"][key] for run in report["runs"]})
                    self.assertIn(key, CHECK.render_markdown(report))

    def test_known_and_unknown_metadata_cannot_be_mixed_across_runs(self):
        for key, sentinel in (("cpuMaxFreqHz", -1), ("memTotalBytes", -1), ("cpuCoreCount", 0)):
            self.set_context_values(**{key: sentinel})
            self.rewrite(lambda data: data["context"].update({key: self.context[key]}), "styles", 2)
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "same device.*context"):
                self.validate()

    def test_unknown_context_does_not_admit_other_values_or_wrong_types(self):
        for key, invalid_values in (
            ("cpuMaxFreqHz", (0, -2, True, 1.5, float("nan"), "-1", None)),
            ("memTotalBytes", (0, -2, False, 1.5, float("nan"))),
            ("cpuCoreCount", (-1, False, 0.5, float("nan"))),
        ):
            for value in invalid_values:
                self.set_context_values(**{key: value})
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    self.validate()

    def test_known_context_has_an_explicit_empty_warning_list(self):
        self.assertEqual([], self.validate()["unknown_metadata_warnings"])

    def test_rejects_missing_apk_hash_mismatch_and_changed_state_source(self):
        original = json.loads(json.dumps(self.manifest))
        for mutate in (
            lambda data: data["runs"][0].pop("apk_sha256"),
            lambda data: data["runs"][0].update(apk_path="missing.apk"),
            lambda data: data["runs"][0].update(apk_sha256="0" * 64),
            lambda data: data["runs"][0].update(source_sha="4" * 40),
        ):
            self.manifest = json.loads(json.dumps(original))
            mutate(self.manifest)
            with self.assertRaises(ValueError):
                self.validate()

    def test_each_state_requires_a_distinct_source_and_apk(self):
        original = json.loads(json.dumps(self.manifest))
        for field_group in (("source_sha",), ("apk_path", "apk_sha256")):
            self.manifest = json.loads(json.dumps(original))
            for run in self.manifest["runs"]:
                for field in field_group:
                    run[field] = original["runs"][0][field]
            with self.subTest(fields=field_group), self.assertRaises(ValueError):
                self.validate()

    def test_copied_json_at_a_new_path_cannot_supply_an_independent_run(self):
        source = json.loads(self.result_path("original", 1).read_text())
        self.result_path("styles", 1).write_text(json.dumps(source, indent=4, sort_keys=True))
        with self.assertRaisesRegex(ValueError, "content.*reused"):
            self.validate()

    def test_cli_distinguishes_pass_inconclusive_and_invalid_inputs(self):
        self.validate()
        command = [sys.executable, str(SCRIPT), str(self.manifest_path), "--format", "json"]
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("PASS", json.loads(result.stdout)["status"])
        self.set_value("styles", 1, "startupCold", 106)
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(3, result.returncode)
        self.manifest_path.write_text("{}")
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(2, result.returncode)


if __name__ == "__main__":
    unittest.main()
