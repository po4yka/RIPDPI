---
id: RST-1790427044011189
title: Prevent signed shared-priors rollback across restarts
kind: bug
status: review
area: rust-native
priority: high
owner: Native and Android
parent: null
blocked_by: []
spec_mode: required
openspec_change: prevent-shared-priors-rollback
created: 2026-09-26
updated: 2026-09-26
status_detail: Implementation and Rust/JNI gates complete; Android unit gate blocked by missing libxray AAR.
---

## Goal

Reject a signed shared-priors bundle older than the last accepted release, including after an app restart.

## Acceptance criteria

- The native apply path verifies a bundle and compares its signed issuance timestamp and payload hash with a durable watermark before replacing the registry.
- Older or conflicting same-timestamp bundles leave the registry and watermark unchanged. Reapplying the same bundle remains safe.
- The Android worker uses an app-private watermark path and records refresh success only after native apply succeeds.
- Rust rollback, restart, invalid-store, and write-failure tests pass. Affected Kotlin and JNI gates pass or their blockers are reported.

## Ownership

One writer owns the Rust verifier/registry, JNI adapter, Kotlin worker, and this task's OpenSpec artifacts. No shared registry or storage-schema file is edited in parallel for this task.
