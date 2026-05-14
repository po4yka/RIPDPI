---
title: Finish native Rust verification for current connectivity/platform diff
type: task
status: done
area: rust-native
priority: high
owner: Senior Rust Native Engineer
parent: null
blocks: []
blocked_by: []
created: 2026-05-04
updated: 2026-05-14
---

- [x] #task Finish native Rust verification for current connectivity/platform diff #repo/RIPDPI #area/rust-native #status/done ⏫ ✅ 2026-05-14

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `finish-native-rust-verification-for-current-connectivity-platform-diff`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-monitor-engine`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-monitor-engine/**`, `native/rust/crates/ripdpi-desync-runtime/**`, `native/rust/crates/ripdpi-diagnostics-probes/**`, `native/rust/crates/ripdpi-android/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

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

## Work log

- Changed files: none for this verification task; the immediately preceding
  diagnostics contract slice committed the required monitor-engine fixes and
  golden fixture.
- Verification:
  - `cargo fmt --manifest-path native/rust/Cargo.toml --all -- --check`
    exited 0.
  - `cargo nextest run --manifest-path native/rust/Cargo.toml -p
    ripdpi-monitor-engine` exited 0 with 108 passed and 2 skipped.
  - `cargo test --manifest-path native/rust/Cargo.toml -p
    ripdpi-desync-runtime` exited 0 with 100 passed.
  - `cargo check --manifest-path native/rust/Cargo.toml -p ripdpi-android`
    exited 0.
- `ripdpi-diagnostics-probes` is not present in current
  `cargo metadata --manifest-path native/rust/Cargo.toml --no-deps`; the
  previous probes facade check is therefore not applicable to this checkout.
- Failures: none.
- Residual risk: no live network or Android native packaging build was run for
  this verification task; external checklist blockers remain tracked by the
  feature-gap readiness preflight.
