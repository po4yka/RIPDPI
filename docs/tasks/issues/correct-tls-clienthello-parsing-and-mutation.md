---
id: RST-1794212008025103
title: Correct TLS ClientHello parsing and mutation
kind: bug
status: done
area: rust-native
priority: high
owner: TLS maintenance
parent: null
blocked_by: []
spec_mode: required
openspec_change: rst-1794212008025103-correct-tls-clienthello-parsing-and-mutation
created: 2026-11-09
updated: 2026-11-10
closed_at: "2026-11-09T22:16:17Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Full CI passed on 8480ede162947052316948e59217c6dfb961d1a0: https://github.com/po4yka/RIPDPI/actions/runs/42702413061; locked native tests and ci-gates passed."
---

## Goal

Inspect and mutate TLS ClientHello without crossing SNI boundaries, losing fragmented names, dropping trailing records, or placing padding after PSK.

## Acceptance criteria

- Regression tests cover all four audit findings and relevant failure paths.
- Targeted locked tests, independent review, and required source gates pass or have an explicit blocker.
- Ownership: SNI bounds, record-aware consumers, and mutation use separate worktrees; the integrator owns shared tests, API snapshots, and combined validation.

## CI repair ownership

- Fuzz lock owner: `native/rust/fuzz/Cargo.lock`; serialize all changes to this file.
- JNI handle owner: `native/rust/crates/ripdpi-android-fetch-adapter/` and `native/rust/crates/ripdpi-android/src/` ECH bridge.
- Integrator owns task records, API snapshots, and combined validation.

## Tor SQLite CI blocker ownership

- Tor dependency owner: `native/rust/Cargo.toml`, `native/rust/Cargo.lock`, `native/rust/fuzz/Cargo.lock`, and the minimal `tor-dirmgr` vendor patch. Serialize these files.
- Integrator owns task and OpenSpec records and combined validation.

## Diagnostics CI blocker ownership

- Diagnostics owner: `core/diagnostics/` Kotlin sources and tests for ECH and HTTPS RR. No baseline changes.
- Integrator owns task and OpenSpec records and combined validation.
