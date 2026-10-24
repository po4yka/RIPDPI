---
id: RST-1792824527607819
title: Fix crypto-bigint constant-time advisory
kind: bug
status: review
area: rust-native
priority: high
owner: Security release
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-24
updated: 2026-10-24
spec_reason: dependency-only
status_detail: Patched lock passed exact cargo-deny policy, source review, 35 SSH library tests, 7 loopback tests, and 3 independent upstream SSH cases; integration owner retains hosted CI and Android artifact acceptance.
---

## Goal

Use the patched crypto-bigint release in the native SSH crypto stack so the existing cargo-deny gate no longer reports RUSTSEC-2026-0387.

## Acceptance criteria

- Lock crypto-bigint 0.7.6 and its required ctutils 0.4.5 patch; preserve all other package versions and policy files.
- Pass the pinned cargo-deny 0.19.8 policy and advisory waiver check.
- Pass the real ripdpi-ssh crypto consumer tests and independent SSH interoperability checks.
- Record exact local evidence and leave hosted CI and rebuilt Android artifacts pending until the integration owner verifies them.
