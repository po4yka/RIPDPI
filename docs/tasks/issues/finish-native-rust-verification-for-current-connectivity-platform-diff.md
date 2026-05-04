---
title: Finish native Rust verification for current connectivity/platform diff
type: task
status: doing
area: rust-native
priority: high
owner: Senior Rust Native Engineer
parent: null
blocks: []
blocked_by: []
created: 2026-05-04
updated: 2026-05-04
---

- [ ] #task Finish native Rust verification for current connectivity/platform diff #repo/RIPDPI #area/rust-native #status/doing ⏫

## Objective
Own implementation-level validation and any small corrective edits for the current native/rust connectivity/platform diff.

## Context
Parent POY-3 found changes in native/rust/Cargo.lock, ripdpi-android Cargo.toml/src/ffi.rs, ripdpi-desync-runtime platform traits and test support, ripdpi-diagnostics-probes facade, ripdpi-monitor-engine connectivity runner split, monitor-engine dependencies, and ripdpi-proxy-runtime desync platform implementation.

Priority:
High.

Parent issue or goal linkage:

## Acceptance criteria
- Ensure the current diff compiles and preserves existing behavior for connectivity runner stages.
- Ensure dependency removals from ripdpi-android and ripdpi-monitor-engine are justified by actual imports, not accidental under-linking.
- Ensure TcpDesyncPlatform trait decomposition remains compatible with registry/dispatch wrapper usage and test support.
- Make only minimal corrective edits if verification exposes issues; do not broaden scope.
- Post a handoff summary listing changed files, verification run, failures, and residual risk.

Expected artifact:
Verified native diff or minimal patch plus Paperclip handoff summary.

Constraints:
No live network experiments. No Android signing/release changes. Preserve unrelated working tree changes.

## Risks
Compile-only success may miss diagnostics behavior drift; coordinate with Network Protocol and QA for behavior coverage.

## Required verification
At minimum: cargo fmt check for affected workspace, cargo test -p ripdpi-monitor-engine, cargo test -p ripdpi-desync-runtime, cargo test -p ripdpi-diagnostics-probes, and a compile check for ripdpi-android. If JNI/native artifact boundaries are affected, request Android native build verification from Build/Gradle.

## Definition of done
Implementation verification evidence is posted, any corrective patch is scoped, and all required review gates are linked.
