---
id: DAT-1791477482125477
title: Reject unsupported subscription multiplex modes
kind: bug
status: done
area: data
priority: high
owner: Mux import writer
parent: null
blocked_by: []
spec_mode: required
openspec_change: reject-subscription-multiplex
created: 2026-10-08
updated: 2026-10-08
closed_at: "2026-10-08T17:10:45Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Commit 5144ad2e7b6c67dc59145caf90b6959a7bdbe2a5: 65 parser and contract tests and affected lint passed; combined tree 5479d468752a4e17812deaae0f6e482e4880efd0 has 122 passing JVM tests, unchanged architecture health, and valid Cargo metadata."
---

## Goal

Reject enabled sing-box multiplex profiles instead of changing their wire mode during import.

## Acceptance criteria

Regression tests reject enabled multiplex with a typed, non-secret reason and preserve explicit Vision, disabled multiplex, and no-flow VLESS REALITY profiles.

## Ownership

Mux import writer owns SingBoxSubscriptionParser.kt, VlessRealityImportTest.kt, SingBoxMultiplexImportTest.kt, and this task and change. No shared schema, locale, lockfile, or golden fixture changes. The integration writer regenerates the combined board. The AWG writer owns separate refresh files in a separate worktree.
