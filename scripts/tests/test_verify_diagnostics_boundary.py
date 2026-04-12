from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.ci import verify_diagnostics_boundary


class VerifyDiagnosticsBoundaryTest(unittest.TestCase):
    def write_file(self, root: Path, relative_path: str, content: str) -> None:
        path = root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def test_collect_violations_rejects_core_service_gradle_dependency(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            self.write_file(
                repo_root,
                "core/diagnostics/build.gradle.kts",
                'dependencies { implementation(project(":core:service")) }',
            )
            self.write_file(
                repo_root,
                "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/Diagnostics.kt",
                "package com.poyka.ripdpi.diagnostics\n",
            )

            violations = verify_diagnostics_boundary.collect_violations(repo_root)

            self.assertEqual(1, len(violations))
            self.assertEqual("core/diagnostics/build.gradle.kts", violations[0].path)

    def test_collect_violations_rejects_service_package_references(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            self.write_file(
                repo_root,
                "core/diagnostics/build.gradle.kts",
                'dependencies { implementation(project(":core:data")) }',
            )
            self.write_file(
                repo_root,
                "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/Diagnostics.kt",
                "import com.poyka.ripdpi.services.NetworkHandoverMonitor\n",
            )

            violations = verify_diagnostics_boundary.collect_violations(repo_root)

            self.assertEqual(1, len(violations))
            self.assertEqual(
                "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/Diagnostics.kt",
                violations[0].path,
            )

    def test_collect_violations_accepts_contract_only_boundary(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            self.write_file(
                repo_root,
                "core/diagnostics/build.gradle.kts",
                """
                dependencies {
                    implementation(project(":core:data"))
                    implementation(project(":core:diagnostics-data"))
                    implementation(project(":core:engine"))
                }
                """,
            )
            self.write_file(
                repo_root,
                "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/Diagnostics.kt",
                "import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator\n",
            )

            violations = verify_diagnostics_boundary.collect_violations(repo_root)

            self.assertEqual([], violations)

    def test_format_summary_reports_violation_count(self) -> None:
        summary = verify_diagnostics_boundary.format_summary(
            [
                verify_diagnostics_boundary.Violation(
                    path="core/diagnostics/build.gradle.kts",
                    message="diagnostics module must not depend on :core:service",
                )
            ],
        )

        self.assertIn("Diagnostics boundary verification", summary)
        self.assertIn("Violations: 1", summary)


if __name__ == "__main__":
    unittest.main()
