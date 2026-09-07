---
id: CIC-1788599419664250
title: Build instrumentation APKs once for the API matrix
kind: chore
status: review
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
---

## Goal

Build the Full and Simple app/test APK pairs once and run the same verified bundle on all five API emulators.

## Acceptance criteria

- One required producer builds x86_64 app/test APKs and exports resolved Orchestrator utilities.
- Five API consumers verify the same-SHA artifact and run without Gradle compilation.
- Preserve Orchestrator isolation, Full/Simple filters, API-35 JNI/Xray tests, and existing JUnit checks.
- Reject corrupt bundles, incomplete instrumentation, failures, and missing test evidence.

## Verification

- 151 workflow contract tests pass, including bundle integrity and runner failure checks.
- Android JUnit validation, device-session, and E2E workflow contracts pass.
- Gradle application configuration and `:app:ktlintKotlinScriptCheck` pass.
- `actionlint`, pinned-action validation, architecture health, and `cargo metadata --locked` pass.
- Local APK task graph resolution is blocked by uncached macOS protoc 4.36.0. The online attempt timed out; offline resolution also lacks the Orchestrator dependency.
- The existing local-network-fixture cold-start test exceeds its five-second limit on both this tree and base commit ce4c2dc8d. Its source is unchanged.
- Hosted APK production and all five emulator runs remain to be verified. Keep this task in review until CI provides that evidence.
