from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from scripts.ci import check_file_loc_limits


class CheckFileLocLimitsTest(unittest.TestCase):
    def test_rust_code_lines_ignore_nested_comments(self) -> None:
        source = """
        fn main() {
            let message = "keep // inside string";
            /* outer
                /* inner */
            */
            println!("{message}");
        }
        """

        self.assertEqual(4, check_file_loc_limits.count_code_lines(source, "rust"))

    def test_kotlin_code_lines_ignore_comments_and_blank_lines(self) -> None:
        source = """
        package sample

        fun main() {
            val value = "/* keep */"
            // comment
            println(value)
        }
        """

        self.assertEqual(5, check_file_loc_limits.count_code_lines(source, "kotlin"))

    def test_kotlin_triple_quoted_strings_preserve_comment_markers(self) -> None:
        source = '''
        package sample

        val text = """
            // still text
            /* still text */
        """.trimIndent()
        '''

        self.assertEqual(5, check_file_loc_limits.count_code_lines(source, "kotlin"))

    def test_rust_raw_strings_preserve_comment_markers(self) -> None:
        source = r'''
        fn main() {
            let regex = r#"// not comment /* not comment */"#;
            println!("{}", regex);
        }
        '''

        self.assertEqual(4, check_file_loc_limits.count_code_lines(source, "rust"))

    def test_compose_detection_supports_annotation_and_imports(self) -> None:
        compose_source = """
        import androidx.compose.runtime.Composable

        @Composable
        fun Screen() {}
        """
        activity_source = """
        import androidx.activity.compose.setContent

        fun attach() {
            setContent {
                Unit
            }
        }
        """
        plain_source = """
        package sample.ui

        data class UiState(val value: String)
        """

        self.assertTrue(check_file_loc_limits.is_compose_source(compose_source))
        self.assertTrue(check_file_loc_limits.is_compose_source(activity_source))
        self.assertFalse(check_file_loc_limits.is_compose_source(plain_source))

    def test_scope_filters_exclude_tests_vendored_and_build_outputs(self) -> None:
        tracked = [
            "app/src/main/java/com/poyka/ripdpi/ui/screens/HomeScreen.kt",
            "app/src/test/java/com/poyka/ripdpi/ui/screens/HomeScreenTest.kt",
            "core/service/build/generated/Test.kt",
            "native/rust/crates/ripdpi-runtime/src/lib.rs",
            "native/rust/third_party/byedpi/crates/ciadpi-bin/src/runtime.rs",
        ]

        paths = check_file_loc_limits.iter_source_paths(Path("/repo"), tracked)

        self.assertEqual(
            [
                Path("app/src/main/java/com/poyka/ripdpi/ui/screens/HomeScreen.kt"),
                Path("native/rust/crates/ripdpi-runtime/src/lib.rs"),
            ],
            paths,
        )

    def test_baseline_exempts_known_violations_and_flags_new_ones(self) -> None:
        measurements = [
            check_file_loc_limits.SourceMeasurement(
                path="app/src/main/java/com/poyka/ripdpi/ui/screens/DnsSettingsScreen.kt",
                kind="compose",
                measured_loc=1200,
                limit=1000,
            ),
            check_file_loc_limits.SourceMeasurement(
                path="core/data/src/main/java/com/poyka/ripdpi/data/diagnostics/DiagnosticsDatabase.kt",
                kind="kotlin",
                measured_loc=900,
                limit=700,
            ),
        ]
        baseline = {
            (
                "app/src/main/java/com/poyka/ripdpi/ui/screens/DnsSettingsScreen.kt",
                "compose",
                1000,
            ): check_file_loc_limits.BaselineEntry(
                path="app/src/main/java/com/poyka/ripdpi/ui/screens/DnsSettingsScreen.kt",
                kind="compose",
                measured_loc=1180,
                limit=1000,
            ),
        }

        results = check_file_loc_limits.evaluate_measurements(measurements, baseline)

        self.assertEqual(1, len(results["baselineExemptions"]))
        self.assertEqual(1, len(results["newViolations"]))
        self.assertEqual(
            "core/data/src/main/java/com/poyka/ripdpi/data/diagnostics/DiagnosticsDatabase.kt",
            results["newViolations"][0]["path"],
        )

    def test_stale_and_missing_baseline_entries_are_reported(self) -> None:
        measurements = [
            check_file_loc_limits.SourceMeasurement(
                path="app/src/main/java/com/poyka/ripdpi/activities/MainViewModel.kt",
                kind="kotlin",
                measured_loc=650,
                limit=700,
            ),
        ]
        baseline = {
            ("app/src/main/java/com/poyka/ripdpi/activities/MainViewModel.kt", "kotlin", 700):
                check_file_loc_limits.BaselineEntry(
                    path="app/src/main/java/com/poyka/ripdpi/activities/MainViewModel.kt",
                    kind="kotlin",
                    measured_loc=720,
                    limit=700,
                ),
            ("native/rust/crates/ripdpi-runtime/src/runtime_policy.rs", "rust", 1500):
                check_file_loc_limits.BaselineEntry(
                    path="native/rust/crates/ripdpi-runtime/src/runtime_policy.rs",
                    kind="rust",
                    measured_loc=1600,
                    limit=1500,
                ),
        }

        results = check_file_loc_limits.evaluate_measurements(measurements, baseline)

        self.assertEqual(1, len(results["staleBaselineEntries"]))
        self.assertEqual(1, len(results["missingBaselineEntries"]))

    def test_read_baseline_requires_entries_list(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            baseline_path = Path(temp_dir) / "baseline.json"
            baseline_path.write_text(json.dumps({"notEntries": []}), encoding="utf-8")

            with self.assertRaises(ValueError):
                check_file_loc_limits.read_baseline(baseline_path)

    def test_build_baseline_only_contains_over_limit_entries(self) -> None:
        measurements = [
            check_file_loc_limits.SourceMeasurement(
                path="native/rust/crates/ripdpi-runtime/src/lib.rs",
                kind="rust",
                measured_loc=1400,
                limit=1500,
            ),
            check_file_loc_limits.SourceMeasurement(
                path="native/rust/crates/ripdpi-monitor/src/lib.rs",
                kind="rust",
                measured_loc=5200,
                limit=1500,
            ),
        ]

        baseline = check_file_loc_limits.build_baseline(measurements)

        self.assertEqual(1, len(baseline["entries"]))
        self.assertEqual("native/rust/crates/ripdpi-monitor/src/lib.rs", baseline["entries"][0]["path"])


if __name__ == "__main__":
    unittest.main()
