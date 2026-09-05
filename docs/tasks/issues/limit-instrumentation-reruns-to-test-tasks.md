---
id: CIC-1788598548629483
title: Limit instrumentation reruns to test tasks
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
closed_at: "2026-09-05T08:59:43Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Regression failed before the change; all 144 workflow-only contract tests passed afterward. Gradle 9.7.1 execution probe confirmed --rerun executes the selected task while its dependency remains UP-TO-DATE. Pinned AGP 9.3.2 bytecode connects androidTestUtil to the test runner APK installer. actionlint, pinact, architecture health, cargo metadata --locked, and diff checks passed. App task help was blocked by missing offline Kotlin dependencies; the online retry stalled and was stopped. Emulator validation remains for CI.
---

## Goal

Run Simple and Xray instrumentation again without forcing their Gradle build dependencies to run again.

## Acceptance criteria

- Replace the two full-graph reruns with task-specific reruns.
- Preserve AGP test utility installation, test filters, and JUnit evidence checks.
- Verify the task option and guard both workflow invocations with a regression test.
