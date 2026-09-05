---
id: CIC-1788597601154879
title: Isolate Gradle cache writers
kind: bug
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
closed_at: "2026-09-05T08:46:33Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: The cache collision regression failed before the fix and passed after it. All 142 workflow-only contract tests passed. actionlint, pinact v4.1.1, architecture health, cargo metadata --locked, and git diff --check passed. Hosted cache writes require the next CI run.
---

## Goal

Let Kotlin coverage and pluggable transport builds save their Gradle cache
without competing for the same immutable GitHub Actions key.

## Acceptance criteria

- Keep one writer per cache namespace and preserve read-only PR access.
- Preserve the default cache keys for existing consumers.
- Let CI and release-candidate transport jobs restore the transport cache.
- Verify namespace isolation with a regression test and workflow checks.
