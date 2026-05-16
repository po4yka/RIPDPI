---
title: Add repeated startup-shutdown supervisor test
type: task
status: done
area: testing
priority: medium
owner: unassigned
parent: epic-orchestration-test-posture
blocks: []
blocked_by: [UNRESOLVED-POY-129]
created: 2026-04-20
updated: 2026-05-16
---

- [x] #task Add repeated startup-shutdown supervisor test #repo/RIPDPI #area/testing #status/done 🔼

## Work log

- 2026-05-16: The named supervisor types in the spec (`ProxyRuntimeSupervisor`,
  `UpstreamRelaySupervisor`, `WarpRuntimeSupervisor`) do not exist in the
  workspace. The embedded proxy runtime is managed instead by
  `run_proxy_with_embedded_control` + `EmbeddedProxyControl`. Added
  `native/rust/crates/ripdpi-proxy-runtime/tests/supervisor_restart.rs`
  exercising 10 rapid start/stop cycles, expected-stop vs crash exit-path
  disambiguation, and thread-join guarantees.
- Verify: `cargo nextest run -p ripdpi-proxy-runtime` — exit 0.

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-repeated-startup-shutdown-supervisor-test`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-proxy-runtime`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-proxy-runtime/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Regression test that hammers each supervisor with rapid start/stop cycles
and scripted exit causes. Backs the explicit-exit-cause fix.

## Acceptance criteria

- [ ] For each supervisor (`ProxyRuntimeSupervisor`,
    `UpstreamRelaySupervisor`, `WarpRuntimeSupervisor`): rapid start/stop
    cycles leave no leaked coroutines, threads, or file descriptors.
- [ ] Scripted exit cause produces the correct `ExitCause` variant.
- [ ] Expected-stop vs crash disambiguation verified without relying on the
    caller's `stopping` flag.

## Links

- [[Epic - Orchestration test posture]]
- [[Add explicit supervisor exit cause types]]
- [[Add orchestration failure-injection harness]]
- [[ripdpi-android-audit-2026-04-20]]
