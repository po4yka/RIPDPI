#!/usr/bin/env python3
"""Fail closed before expensive CI jobs start."""

from __future__ import annotations

import json
import os
from collections.abc import Mapping


ALLOWED_RESULTS = frozenset({"success", "skipped"})
ALWAYS_REQUIRED = frozenset({"change-routing", "android-e2e-result-contracts"})
NON_SCHEDULE_REQUIRED = frozenset(
    {
        "design-md-lint",
        "architecture-health",
        "release-gates",
        "cargo-deny",
        "gradle-static-analysis",
    }
)


def required_jobs(
    *,
    event_name: str,
    route: str,
    run_full_ci: bool,
    run_fixtures_ci: bool,
    run_workflow_ci: bool,
) -> set[str]:
    required = set(ALWAYS_REQUIRED)
    if route == "documentation":
        required.add("docs-only-gate")
    elif event_name != "schedule" and run_full_ci:
        required.update(NON_SCHEDULE_REQUIRED)
    if run_fixtures_ci:
        required.add("fixture-contracts")
    if run_workflow_ci:
        required.add("workflow-only-contracts")
    return required


def preflight_errors(
    needs: Mapping[str, Mapping[str, object]],
    *,
    event_name: str,
    route: str,
    run_full_ci: bool,
    run_fixtures_ci: bool,
    run_workflow_ci: bool,
) -> list[str]:
    results = {job: str(detail.get("result", "missing")) for job, detail in needs.items()}
    errors = [
        f"unexpected result: {job}={result}"
        for job, result in sorted(results.items())
        if result not in ALLOWED_RESULTS
    ]
    for job in sorted(
        required_jobs(
            event_name=event_name,
            route=route,
            run_full_ci=run_full_ci,
            run_fixtures_ci=run_fixtures_ci,
            run_workflow_ci=run_workflow_ci,
        )
    ):
        if results.get(job) != "success":
            errors.append(f"required gate did not succeed: {job}={results.get(job, 'missing')}")
    return errors


def env_bool(name: str) -> bool:
    return os.environ.get(name, "").lower() == "true"


def main() -> int:
    needs = json.loads(os.environ["NEEDS_JSON"])
    errors = preflight_errors(
        needs,
        event_name=os.environ["EVENT_NAME"],
        route=os.environ["ROUTE"],
        run_full_ci=env_bool("RUN_FULL_CI"),
        run_fixtures_ci=env_bool("RUN_FIXTURES_CI"),
        run_workflow_ci=env_bool("RUN_WORKFLOW_CI"),
    )
    if errors:
        for error in errors:
            print(f"CI preflight failed: {error}")
        return 1
    print("CI preflight passed; expensive jobs may start.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
