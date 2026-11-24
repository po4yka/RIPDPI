import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


CHECKER = Path(__file__).resolve().parents[1] / "ci/check-macrobenchmark-regressions.py"
METRICS = ("timeToInitialDisplayMs", "timeToFullDisplayMs")


def benchmark(name, count, milliseconds):
    return {
        "className": "com.poyka.ripdpi.baselineprofile.StartupBenchmark",
        "name": name,
        "metrics": {
            metric: {"median": milliseconds, "runs": [milliseconds] * count}
            for metric in METRICS
        },
    }


class MacrobenchmarkRegressionTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.results = self.root / "connected" / "ripdpi-benchmarkData.json"
        self.results.parent.mkdir()
        self.reference = self.root / "reference.json"
        self.cold = benchmark("startupCold[mode=COLD]", 15, 800)
        self.warm = benchmark("startupWarm[mode=WARM]", 10, 200)

    def write_results(self, *benchmarks):
        self.results.write_text(json.dumps({"benchmarks": benchmarks}))

    def run_checker(self, *args):
        return subprocess.run(
            [
                sys.executable, str(CHECKER), "--results-dir", str(self.results.parent),
                "--baseline", str(self.reference), *args,
            ],
            capture_output=True, text=True, check=False,
        )

    def record_reference(self):
        self.write_results(self.cold, self.warm)
        result = self.run_checker("--dump-current")
        self.assertEqual(0, result.returncode, result.stderr)
        self.reference.write_text(result.stdout)

    def test_checks_current_data_and_rejects_regression(self):
        self.record_reference()
        result = self.run_checker()
        self.assertEqual(0, result.returncode, result.stderr)
        self.write_results(self.cold, benchmark("startupWarm[mode=WARM]", 10, 240))
        result = self.run_checker()
        self.assertEqual(1, result.returncode)
        self.assertIn("startupWarm/timeToFullDisplayMs median regression", result.stderr)

    def test_cannot_record_without_both_complete_full_display_series(self):
        self.warm["metrics"]["timeToFullDisplayMs"]["runs"].pop()
        self.write_results(self.cold, self.warm)
        result = self.run_checker("--dump-current")
        self.assertEqual(1, result.returncode)
        self.assertIn("startupWarm/timeToFullDisplayMs", result.stderr)

    def test_extra_samples_are_accepted_but_display_series_must_match(self):
        cold = benchmark("startupCold", 16, 800)
        warm = benchmark("startupWarm", 11, 200)
        self.write_results(cold, warm)
        self.assertEqual(0, self.run_checker("--dump-current").returncode)
        warm["metrics"]["timeToFullDisplayMs"]["runs"].pop()
        self.write_results(cold, warm)
        result = self.run_checker("--dump-current")
        self.assertEqual(1, result.returncode)
        self.assertIn("Unpaired startup metrics: startupWarm", result.stderr)

    def test_missing_case_or_duplicate_device_result_fails(self):
        self.write_results(self.cold)
        result = self.run_checker("--dump-current")
        self.assertEqual(1, result.returncode)
        self.assertIn("Missing startup benchmarks: startupWarm", result.stderr)
        self.write_results(self.cold, self.cold, self.warm)
        result = self.run_checker("--dump-current")
        self.assertEqual(1, result.returncode)
        self.assertIn("Duplicate startup benchmark", result.stderr)

    def test_empty_reference_fails_closed(self):
        self.write_results(self.cold, self.warm)
        self.reference.write_text('{"benchmarks": {}}')
        result = self.run_checker()
        self.assertEqual(1, result.returncode)
        self.assertIn("Missing or invalid startup reference", result.stderr)
