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

    def test_scope_filters_exclude_tests_non_crate_and_build_outputs(self) -> None:
        tracked = [
            "app/src/main/kotlin/com/poyka/ripdpi/ui/screens/HomeScreen.kt",
            "app/src/test/kotlin/com/poyka/ripdpi/ui/screens/HomeScreenTest.kt",
            "core/service/build/generated/Test.kt",
            "native/rust/crates/ripdpi-proxy-runtime/src/lib.rs",
            "native/rust/vendor/example/src/runtime.rs",
        ]

        paths = check_file_loc_limits.iter_source_paths(Path("/repo"), tracked)

        self.assertEqual(
            [
                Path("app/src/main/kotlin/com/poyka/ripdpi/ui/screens/HomeScreen.kt"),
                Path("native/rust/crates/ripdpi-proxy-runtime/src/lib.rs"),
            ],
            paths,
        )

    def test_baseline_exempts_known_violations_and_flags_new_ones(self) -> None:
        measurements = [
            check_file_loc_limits.SourceMeasurement(
                path="app/src/main/kotlin/com/poyka/ripdpi/ui/screens/DnsSettingsScreen.kt",
                kind="compose",
                measured_loc=1200,
                limit=1000,
            ),
            check_file_loc_limits.SourceMeasurement(
                path="core/data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/DiagnosticsDatabase.kt",
                kind="kotlin",
                measured_loc=900,
                limit=700,
            ),
        ]
        baseline = {
            (
                "app/src/main/kotlin/com/poyka/ripdpi/ui/screens/DnsSettingsScreen.kt",
                "compose",
                1000,
            ): check_file_loc_limits.BaselineEntry(
                path="app/src/main/kotlin/com/poyka/ripdpi/ui/screens/DnsSettingsScreen.kt",
                kind="compose",
                measured_loc=1180,
                limit=1000,
            ),
        }

        results = check_file_loc_limits.evaluate_measurements(measurements, baseline)

        self.assertEqual(1, len(results["baselineExemptions"]))
        self.assertEqual(1, len(results["baselineGrowthViolations"]))
        self.assertEqual(20, results["baselineGrowthViolations"][0]["growth"])
        self.assertEqual(1, len(results["newViolations"]))
        self.assertEqual(
            "core/data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/DiagnosticsDatabase.kt",
            results["newViolations"][0]["path"],
        )

    def test_baseline_exemption_without_growth_does_not_fail_growth_gate(self) -> None:
        measurements = [
            check_file_loc_limits.SourceMeasurement(
                path="native/rust/crates/ripdpi-runtime-policy/src/runtime_policy.rs",
                kind="rust",
                measured_loc=1600,
                limit=1500,
            ),
        ]
        baseline = {
            ("native/rust/crates/ripdpi-runtime-policy/src/runtime_policy.rs", "rust", 1500):
                check_file_loc_limits.BaselineEntry(
                    path="native/rust/crates/ripdpi-runtime-policy/src/runtime_policy.rs",
                    kind="rust",
                    measured_loc=1600,
                    limit=1500,
                ),
        }

        results = check_file_loc_limits.evaluate_measurements(measurements, baseline)

        self.assertEqual(1, len(results["baselineExemptions"]))
        self.assertEqual([], results["baselineGrowthViolations"])

    def test_stale_and_missing_baseline_entries_are_reported(self) -> None:
        measurements = [
            check_file_loc_limits.SourceMeasurement(
                path="app/src/main/kotlin/com/poyka/ripdpi/activities/MainViewModel.kt",
                kind="kotlin",
                measured_loc=650,
                limit=700,
            ),
        ]
        baseline = {
            ("app/src/main/kotlin/com/poyka/ripdpi/activities/MainViewModel.kt", "kotlin", 700):
                check_file_loc_limits.BaselineEntry(
                    path="app/src/main/kotlin/com/poyka/ripdpi/activities/MainViewModel.kt",
                    kind="kotlin",
                    measured_loc=720,
                    limit=700,
                ),
            ("native/rust/crates/ripdpi-runtime-policy/src/runtime_policy.rs", "rust", 1500):
                check_file_loc_limits.BaselineEntry(
                    path="native/rust/crates/ripdpi-runtime-policy/src/runtime_policy.rs",
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
                path="native/rust/crates/ripdpi-proxy-runtime/src/lib.rs",
                kind="rust",
                measured_loc=1400,
                limit=1500,
            ),
            check_file_loc_limits.SourceMeasurement(
                path="native/rust/crates/ripdpi-monitor-engine/src/lib.rs",
                kind="rust",
                measured_loc=5200,
                limit=1500,
            ),
        ]

        baseline = check_file_loc_limits.build_baseline(measurements)

        self.assertEqual(1, len(baseline["entries"]))
        self.assertEqual("native/rust/crates/ripdpi-monitor-engine/src/lib.rs", baseline["entries"][0]["path"])

    def test_top_functions_does_not_consume_later_body_for_abstract_kotlin_function(self) -> None:
        source = """
        abstract class Bindings {
            abstract fun bindService(
                implementation: ServiceImplementation,
            ): Service

            companion object {
                fun provideService(): Service {
                    return ServiceImplementation()
                }
            }
        }
        """
        with tempfile.TemporaryDirectory() as temp_dir:
            source_path = Path(temp_dir) / "Bindings.kt"
            source_path.write_text(source, encoding="utf-8")

            functions = dict(check_file_loc_limits.top_functions(source_path, "kotlin"))

        self.assertEqual(3, functions["bindService"])
        self.assertEqual(3, functions["provideService"])

    def test_bodyless_interface_methods_do_not_consume_later_class(self) -> None:
        source = """internal interface Reader {
    suspend fun read(
        request: Request,
    ): Value
    fun reset()
}
class Implementation {
    fun execute() {
        first()
        second()
    }
}
"""
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "Reader.kt"
            path.write_text(source, encoding="utf-8")
            functions = dict(check_file_loc_limits.top_functions(path, "kotlin"))
        self.assertEqual(3, functions["read"])
        self.assertEqual(1, functions["reset"])
        self.assertEqual(4, functions["execute"])

    def test_default_interface_block_on_next_line_remains_measured(self) -> None:
        source = """interface Reader {
    fun defaultValue(): Value
    // The implementation body can start on the next line.
    {
        first()
        second()
        return value
    }
}
"""
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "Reader.kt"
            path.write_text(source, encoding="utf-8")
            functions = dict(check_file_loc_limits.top_functions(path, "kotlin"))
        self.assertEqual(7, functions["defaultValue"])

    def test_long_default_interface_expression_body_remains_measured(self) -> None:
        body = "\n".join("        next()" for _ in range(130))
        source = "interface Reader {\n    fun execute() = run {\n" + body + "\n    }\n}\n"
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "Reader.kt"
            path.write_text(source, encoding="utf-8")
            functions = dict(check_file_loc_limits.top_functions(path, "kotlin"))
        self.assertEqual(132, functions["execute"])

    def test_expression_call_does_not_consume_following_class(self) -> None:
        other_body = "\n".join("        next()" for _ in range(130))
        source = """class Projection {
    fun project(value: Value) = Result(
        value = value,
    )
}
class Other {
    fun execute() {
""" + other_body + "\n    }\n}\n"
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "Projection.kt"
            path.write_text(source, encoding="utf-8")
            functions = dict(check_file_loc_limits.top_functions(path, "kotlin"))
        self.assertEqual(3, functions["project"])
        self.assertEqual(132, functions["execute"])

    def test_long_expression_call_without_lambda_remains_measured(self) -> None:
        arguments = "\n".join("    value," for _ in range(130))
        source = "fun project() = Result(\n" + arguments + "\n)\n"
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "Projection.kt"
            path.write_text(source, encoding="utf-8")
            functions = dict(check_file_loc_limits.top_functions(path, "kotlin"))
        self.assertEqual(132, functions["project"])

    def test_expression_chain_includes_every_lambda(self) -> None:
        body = "\n".join("    next()" for _ in range(130))
        source = "fun project() = run {\n    first()\n}\n    .also {\n" + body + "\n    }\nfun after() = Unit\n"
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "Projection.kt"
            path.write_text(source, encoding="utf-8")
            functions = dict(check_file_loc_limits.top_functions(path, "kotlin"))
        self.assertEqual(135, functions["project"])
        self.assertEqual(1, functions["after"])

    def test_expression_literal_retains_all_physical_lines(self) -> None:
        literal = "\n".join("    } // still a literal" for _ in range(130))
        source = 'fun text() = """\n' + literal + '\n"""\nfun after() = Unit\n'
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "Projection.kt"
            path.write_text(source, encoding="utf-8")
            functions = dict(check_file_loc_limits.top_functions(path, "kotlin"))
        self.assertEqual(132, functions["text"])
        self.assertEqual(1, functions["after"])

    def test_expression_conditional_includes_else_branch(self) -> None:
        body = "\n".join("    next()" for _ in range(130))
        source = "fun choose() = if (condition) {\n    first()\n}\nelse {\n" + body + "\n}\nfun after() = Unit\n"
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "Projection.kt"
            path.write_text(source, encoding="utf-8")
            functions = dict(check_file_loc_limits.top_functions(path, "kotlin"))
        self.assertEqual(135, functions["choose"])
        self.assertEqual(1, functions["after"])

    def test_expression_nested_lambda_includes_call_closing_line(self) -> None:
        source = """fun project() = Result(
    callback = {
        next()
    },
    marker = "})",
)
fun after() = Unit
"""
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "Projection.kt"
            path.write_text(source, encoding="utf-8")
            functions = dict(check_file_loc_limits.top_functions(path, "kotlin"))
        self.assertEqual(6, functions["project"])
        self.assertEqual(1, functions["after"])

    def test_expression_unbraced_conditional_retains_both_branches(self) -> None:
        source = """fun choose() =
    if (condition)
        first()
    else
        second()
fun after() = Unit
"""
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "Conditional.kt"
            path.write_text(source, encoding="utf-8")
            functions = dict(check_file_loc_limits.top_functions(path, "kotlin"))
        self.assertEqual(5, functions["choose"])
        self.assertEqual(1, functions["after"])

    def test_expression_comments_do_not_end_an_incomplete_call(self) -> None:
        source = """fun project() =
    // Misleading braces { } and brackets ] inside comments.
    Result(
        first = first(), /* ) } */
        second = second(),
    )
fun after() = Unit
"""
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "Projection.kt"
            path.write_text(source, encoding="utf-8")
            functions = dict(check_file_loc_limits.top_functions(path, "kotlin"))
        self.assertEqual(6, functions["project"])
        self.assertEqual(1, functions["after"])

    def test_expression_prefix_and_arbitrary_infix_retain_long_rhs(self) -> None:
        body = "\n".join("        next()" for _ in range(130))
        for prefix in ("!", "bits and", "first() custom", "left to", "value +"):
            with self.subTest(prefix=prefix), tempfile.TemporaryDirectory() as temp_dir:
                source = "fun choose() =\n    " + prefix + "\n    run {\n" + body + "\n    }\nfun after() = Unit\n"
                path = Path(temp_dir) / "Operators.kt"
                path.write_text(source, encoding="utf-8")
                functions = dict(check_file_loc_limits.top_functions(path, "kotlin"))
                self.assertEqual(134, functions["choose"])
                self.assertEqual(1, functions["after"])

    def test_expression_complete_postfix_and_identifiers_stop_at_newline(self) -> None:
        for value in ("counter++", "counter--", "value!!", "to", "obj.to", "left to right", "value as Type"):
            with self.subTest(value=value), tempfile.TemporaryDirectory() as temp_dir:
                source = "fun choose() = " + value + "\nfun after() = run {\n    next()\n}\n"
                path = Path(temp_dir) / "Operators.kt"
                path.write_text(source, encoding="utf-8")
                functions = dict(check_file_loc_limits.top_functions(path, "kotlin"))
                self.assertEqual(1, functions["choose"])
                self.assertEqual(3, functions["after"])

    def test_expression_negated_membership_and_type_operators_keep_rhs(self) -> None:
        body = "\n".join("        next()" for _ in range(130))
        for operator in ("!in", "!is", "in", "is", "as", "as?"):
            with self.subTest(operator=operator), tempfile.TemporaryDirectory() as temp_dir:
                rhs = "run {\n" + body + "\n    }" if operator in ("!in", "in") else "String"
                source = "fun choose(value: Any) =\n    value " + operator + "\n    " + rhs + "\nfun after() = Unit\n"
                path = Path(temp_dir) / "Operators.kt"
                path.write_text(source, encoding="utf-8")
                functions = dict(check_file_loc_limits.top_functions(path, "kotlin"))
                self.assertEqual(134 if operator in ("!in", "in") else 3, functions["choose"])
                self.assertEqual(1, functions["after"])

    def test_expression_completed_generic_type_checks_do_not_consume_class(self) -> None:
        body = "\n".join("        next()" for _ in range(130))
        for expression in ("value is List<*>", "value !is List<String>", "value as List<String>", "value as? List<List<String>>?", "value as List<*>?"):
            with self.subTest(expression=expression), tempfile.TemporaryDirectory() as temp_dir:
                source = "fun choose(value: Any) = " + expression + "\nclass Other {\n    fun after() {\n" + body + "\n    }\n}\n"
                path = Path(temp_dir) / "Operators.kt"
                path.write_text(source, encoding="utf-8")
                functions = dict(check_file_loc_limits.top_functions(path, "kotlin"))
                self.assertEqual(1, functions["choose"])
                self.assertEqual(132, functions["after"])

    def test_expression_annotations_and_labels_are_prefixes(self) -> None:
        body = "\n".join("        next()" for _ in range(130))
        for prefix in ('@Suppress("UNUSED_EXPRESSION")', '@pkg.Marker', '@Marker(value = (first()))', 'owner@'):
            with self.subTest(prefix=prefix), tempfile.TemporaryDirectory() as temp_dir:
                source = "fun choose() =\n    " + prefix + "\n    run {\n" + body + "\n    }\nfun after() = Unit\n"
                path = Path(temp_dir) / "Prefixes.kt"
                path.write_text(source, encoding="utf-8")
                functions = dict(check_file_loc_limits.top_functions(path, "kotlin"))
                self.assertEqual(134, functions["choose"])
                self.assertEqual(1, functions["after"])

    def test_expression_when_body_stops_before_following_declaration(self) -> None:
        source = "fun choose(value: Any) =\n    when {\n        value is String -> value\n        else -> Unit\n    }\n@Marker\nfun after() {\n    next()\n}\n"
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "Branches.kt"
            path.write_text(source, encoding="utf-8")
            functions = dict(check_file_loc_limits.top_functions(path, "kotlin"))
            self.assertEqual(5, functions["choose"])
            self.assertEqual(3, functions["after"])

    def test_expression_multiline_type_arguments_retain_long_call(self) -> None:
        body = "\n".join("        value," for _ in range(130))
        source = """fun choose() =
    Result<
        List<String>,
        Int
    >(
""" + body + "\n    )\nfun after() = Unit\n"
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "Generic.kt"
            path.write_text(source, encoding="utf-8")
            functions = dict(check_file_loc_limits.top_functions(path, "kotlin"))
        self.assertEqual(136, functions["choose"])
        self.assertEqual(1, functions["after"])

    def test_bodyless_interface_multiline_return_type_is_counted(self) -> None:
        source = """interface Reader {
    fun read():
        Result<
            Value,
        >
}
class Implementation {
    fun execute() {
        first()
    }
}
"""
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "Reader.kt"
            path.write_text(source, encoding="utf-8")
            functions = dict(check_file_loc_limits.top_functions(path, "kotlin"))
        self.assertEqual(4, functions["read"])
        self.assertEqual(3, functions["execute"])

    def test_bodyless_interface_literal_default_does_not_change_parameter_depth(self) -> None:
        source = '''interface Reader {
    fun read(separator: String = "){"): Value
}
class Implementation {
    fun execute() {
        first()
    }
}
'''
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "Reader.kt"
            path.write_text(source, encoding="utf-8")
            functions = dict(check_file_loc_limits.top_functions(path, "kotlin"))
        self.assertEqual(1, functions["read"])
        self.assertEqual(3, functions["execute"])

    def test_multiline_function_type_return_does_not_hide_implementation(self) -> None:
        source = """interface Reader {
    fun factory(): Result<(Input) ->
        Value> {
        first()
        return result
    }
}
"""
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "Reader.kt"
            path.write_text(source, encoding="utf-8")
            functions = dict(check_file_loc_limits.top_functions(path, "kotlin"))
        self.assertEqual(5, functions["factory"])

    def test_long_interface_block_ignores_braces_in_defaults_and_comments(self) -> None:
        body = "\n".join("        next()" for _ in range(130))
        source = '''interface Reader {
    fun execute(callback: () -> Unit = {}, marker: String = "{}")
    // Signature-to-body comment with misleading { } braces.
    {
''' + body + "\n    }\n}\n"
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "Reader.kt"
            path.write_text(source, encoding="utf-8")
            functions = dict(check_file_loc_limits.top_functions(path, "kotlin"))
        self.assertEqual(134, functions["execute"])

    def test_long_block_with_return_type_prefix_on_next_line_remains_measured(self) -> None:
        body = "\n".join("        next()" for _ in range(130))
        for signature in (
            "fun execute()\n        : Unit",
            "fun execute(): ()\n        -> Unit",
            "fun execute(): List\n        <Unit>",
            "fun execute(): kotlin\n        .Unit",
            "fun execute(): Unit\n        ?",
            "fun execute(): T\n        & Any",
            "fun execute(): T &\n        Any",
            "fun execute(): suspend\n        () -> Unit",
            "fun execute(): suspend\n        String.() -> Unit",
            "fun execute(): suspend\n        @Ann () -> Unit",
        ):
            with self.subTest(signature=signature), tempfile.TemporaryDirectory() as temp_dir:
                source = "interface Reader {\n    " + signature + " {\n" + body + "\n    }\n}\n"
                path = Path(temp_dir) / "Reader.kt"
                path.write_text(source, encoding="utf-8")
                functions = dict(check_file_loc_limits.top_functions(path, "kotlin"))
                self.assertEqual(133, functions["execute"])


if __name__ == "__main__":
    unittest.main()
