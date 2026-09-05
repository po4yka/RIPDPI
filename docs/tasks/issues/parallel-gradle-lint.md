---
id: CIC-1788601192417259
title: Run Gradle lint outside the CI preflight barrier
kind: chore
status: done
area: ci
priority: high
owner: npochaev
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-09-05
updated: 2026-09-05
spec_reason: tooling-only
closed_at: "2026-09-05T09:42:13Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: 152 workflow contract tests pass, including full and Android lint-result enforcement. actionlint and pinact pass. Architecture health has zero new or worsened indicators. Locked Cargo metadata succeeds. Hosted CI timing remains unmeasured.
---

## Goal

Release compile and test jobs after the fast preflight gates, while Gradle static analysis runs independently.

## Acceptance criteria

- Preflight does not depend on Gradle static analysis for full or Android routes.
- The final CI gate still requires successful Gradle static analysis for both routes.
- Workflow contract tests, actionlint, pinact, and repository integration checks pass.
