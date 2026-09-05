from __future__ import annotations

import unittest

from scripts.ci.check_ci_preflight import preflight_errors


COMMON_SUCCESS = {
    "change-routing": {"result": "success"},
    "task-contracts": {"result": "success"},
    "docs-only-gate": {"result": "skipped"},
    "workflow-only-contracts": {"result": "skipped"},
    "fixture-contracts": {"result": "skipped"},
    "android-e2e-result-contracts": {"result": "success"},
    "design-md-lint": {"result": "success"},
    "architecture-health": {"result": "success"},
    "release-gates": {"result": "success"},
    "cargo-deny": {"result": "success"},
}


class CheckCiPreflightTest(unittest.TestCase):
    def errors(
        self, needs: dict[str, dict[str, str]], **overrides: object
    ) -> list[str]:
        inputs: dict[str, object] = {
            "event_name": "pull_request",
            "route": "full",
            "run_full_ci": True,
            "run_android_ci": False,
            "run_rust_native_ci": False,
            "run_fixtures_ci": False,
            "run_workflow_ci": False,
        }
        inputs.update(overrides)
        return preflight_errors(needs, **inputs)  # type: ignore[arg-type]

    def test_full_non_schedule_route_requires_static_gates(self) -> None:
        self.assertEqual([], self.errors(COMMON_SUCCESS))
        for job in (
            "design-md-lint",
            "architecture-health",
            "release-gates",
            "cargo-deny",
        ):
            with self.subTest(job=job):
                needs = {name: dict(detail) for name, detail in COMMON_SUCCESS.items()}
                needs[job]["result"] = "skipped"
                self.assertIn(
                    f"required gate did not succeed: {job}=skipped",
                    self.errors(needs),
                )

    def test_documentation_and_schedule_routes_accept_intentional_skips(self) -> None:
        docs = {name: {"result": "skipped"} for name in COMMON_SUCCESS}
        docs["change-routing"]["result"] = "success"
        docs["task-contracts"]["result"] = "success"
        docs["android-e2e-result-contracts"]["result"] = "success"
        docs["docs-only-gate"]["result"] = "success"
        self.assertEqual(
            [],
            self.errors(docs, route="documentation", run_full_ci=False),
        )

        scheduled = {name: dict(detail) for name, detail in COMMON_SUCCESS.items()}
        for job in (
            "design-md-lint",
            "architecture-health",
            "release-gates",
            "cargo-deny",
        ):
            scheduled[job]["result"] = "skipped"
        self.assertEqual([], self.errors(scheduled, event_name="schedule"))

    def test_targeted_routes_require_only_owned_static_gates(self) -> None:
        android = {name: dict(detail) for name, detail in COMMON_SUCCESS.items()}
        android["cargo-deny"]["result"] = "skipped"
        self.assertEqual(
            [],
            self.errors(
                android,
                route="android",
                run_full_ci=False,
                run_android_ci=True,
            ),
        )
        rust = {name: dict(detail) for name, detail in COMMON_SUCCESS.items()}
        self.assertEqual(
            [],
            self.errors(
                rust,
                route="rust-native",
                run_full_ci=False,
                run_rust_native_ci=True,
            ),
        )

    def test_fixture_and_workflow_routes_require_their_contract_gates(self) -> None:
        fixtures = {name: dict(detail) for name, detail in COMMON_SUCCESS.items()}
        self.assertIn(
            "required gate did not succeed: fixture-contracts=skipped",
            self.errors(fixtures, route="fixtures", run_fixtures_ci=True),
        )
        fixtures["fixture-contracts"]["result"] = "success"
        self.assertEqual(
            [],
            self.errors(fixtures, route="fixtures", run_fixtures_ci=True),
        )

        workflow = {name: dict(detail) for name, detail in COMMON_SUCCESS.items()}
        self.assertIn(
            "required gate did not succeed: workflow-only-contracts=skipped",
            self.errors(workflow, route="workflow", run_workflow_ci=True),
        )

    def test_failure_and_cancellation_fail_even_for_optional_gates(self) -> None:
        for result in ("failure", "cancelled", "timed_out"):
            with self.subTest(result=result):
                needs = {name: dict(detail) for name, detail in COMMON_SUCCESS.items()}
                needs["workflow-only-contracts"]["result"] = result
                self.assertIn(
                    f"unexpected result: workflow-only-contracts={result}",
                    self.errors(needs),
                )


if __name__ == "__main__":
    unittest.main()
