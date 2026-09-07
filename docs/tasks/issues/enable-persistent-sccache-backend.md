---
id: CIC-1788598192613341
title: Enable persistent sccache backend
kind: chore
status: done
area: ci
priority: high
owner: CI maintainer
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-09-05
updated: 2026-09-05
spec_reason: tooling-only
closed_at: "2026-09-05T08:51:33Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: The regression test failed for CI and release-candidate before the change and passed after it. All 143 workflow-only contract tests passed. actionlint, pinact v4.1.1, architecture health, cargo metadata --locked, and git diff --check passed. Hosted cache hit rates require subsequent CI runs.
---

## Goal

Use the GitHub Actions backend to preserve sccache compiler outputs between CI runners.

## Acceptance criteria

- Enable the backend for setup, compilation, and statistics in every workflow that uses sccache.
- Keep existing compiler-wrapper opt-ins and pinned setup actions.
- Verify both CI and release-candidate configuration with a regression test and workflow checks.
