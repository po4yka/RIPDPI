---
id: RST-1788533847908829
title: Upgrade Rust toolchain to 1.98.1
kind: chore
status: done
area: rust-native
priority: high
owner: unassigned
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-09-04
updated: 2026-11-24
spec_reason: tooling-only
closed_at: "2026-11-24T17:26:36Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Published CI run 33946401712 passed on aa6d49ed7f with Rust 1.98.1, four Android ABIs, debug and release package verification, native lint and workspace tests, Miri, cargo-deny, size checks, and the non-rooted API 35 controlled provider TUN suite.
---

## Goal

Build, lint, test, and package the Rust workspace and Android native artifacts
with the exact Rust 1.98.1 toolchain while preserving dependency resolution,
public APIs, JNI exports, and supported Android ABIs. Skip Rust 1.98.0 because
1.98.1 fixes its virtual-table miscompilation that can cause UB or crashes.

## Acceptance criteria

- All live stable-toolchain, MSRV, CI, and contributor-documentation pins resolve
  to Rust 1.98.1; existing nightly pins remain unchanged.
- Workspace formatting, Clippy with warnings denied, tests, rustdoc, cargo-deny,
  Miri, and four-target Android cross-checks pass with locked dependencies.
- Android debug and release native packaging passes for all supported ABIs, and
  ELF, JNI symbol, size, and bloat checks do not require baseline changes.
- `native/rust/Cargo.lock`, public Rust/Kotlin/JNI contracts, protobuf schemas,
  the Rust edition, dependency versions, and the Android NDK remain unchanged.
- A non-rooted device completes VPN or proxy start/stop and one diagnostics or
  relay scenario without a regression.
- After the branch is published, the `ci-required` workflow is terminal-green.
