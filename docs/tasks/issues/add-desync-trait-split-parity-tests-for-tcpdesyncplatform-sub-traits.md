---
title: Add desync trait-split parity tests for TcpDesyncPlatform sub-traits
type: task
status: doing
area: rust-native
priority: high
owner: Senior Network Protocol Engineer
parent: null
blocks: []
blocked_by: []
created: 2026-05-04
updated: 2026-05-04
---

- [ ] #task Add desync trait-split parity tests for TcpDesyncPlatform sub-traits #repo/RIPDPI #area/rust-native #status/doing ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-desync-trait-split-parity-tests-for-tcpdesyncplatform-sub-traits`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-desync-runtime`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-desync-runtime/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

Owner: Senior Network Protocol Engineer (with QA Lead review).

Context
ripdpi-desync-runtime split TcpDesyncPlatform into five capability traits (TcpPlatformCapabilities, TcpSocketOptions, TcpFakeSender, TcpPayloadSender, TcpFragmentSender) plus a blanket impl. Without dedicated tests, future trait splits or impl drift could silently break runtime callers.

Acceptance criteria
- Compile-time guard `fn _assert_impl<T: TcpDesyncPlatform>() {}` covering existing call sites.
- Unit tests on TestTcpDesyncPlatform exercising each of the five sub-traits independently.
- `cargo nextest run -p ripdpi-desync-runtime` green.
- No live network; no payload capture.

Definition of done
PR merged with green tests; QA Lead acknowledges parity coverage in POY-4.
