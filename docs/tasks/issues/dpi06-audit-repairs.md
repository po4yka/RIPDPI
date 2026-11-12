---
id: RST-1794498457070491
title: Repair DPI-06 data safety and execution evidence
kind: bug
status: review
area: rust-native
priority: high
owner: DPI-06 repair lead
parent: null
blocked_by: []
spec_mode: required
openspec_change: dpi06-audit-repairs
created: 2026-11-12
updated: 2026-11-12
status_detail: Seven confirmed findings repaired; scoped checks passed; broader BPF permission limits recorded in verification.
---

## Goal

Repair DPI-06 findings F1 through F7. Preserve application bytes and socket state, and report actual execution.

## Acceptance criteria

Separate fix commits, regression evidence, crate suites, independent reviews, and just ci-gates pass before main integration. Record wire and device evidence limits.

## Ownership

- Lead: portfolio/OpenSpec, helper progress transport, adapter mapping, combined checks, snapshots, integration, push.
- privileged_repairs: ripdpi-privileged-ops F1/F6; separate commits.
- runtime_repairs: ripdpi-desync-runtime F2/F3/F4/F5; separate commits. Lead serializes F1 overlap after this lane.
- docs_repair: docs/native documents and descriptive adapter primitive-probe test text for F7.
- Each writer uses a separate worktree. Lead alone owns task state. No dependency, lockfile, protobuf, JNI, locale, golden, or baseline changes are planned.

## Evidence boundary

Local fixes, commits, main integration, and push are authorized. Public build-image downloads and nonroot Linux local tests are allowed. Root activation, physical devices, and external target probes remain outside scope.
