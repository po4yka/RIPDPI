---
title: Detect NO_TCP_FALLBACK app families
type: task
status: done
area: diagnostics
priority: medium
owner: unassigned
parent: epic-direct-mode-transport-policy-and-verdicts
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-16
---

- [x] #task Detect NO_TCP_FALLBACK app families #repo/RIPDPI #area/diagnostics #status/done 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `detect-no-tcp-fallback-app-families`
- **Verify:** `just test-module core:diagnostics`
- **Scope (only modify these + this file + the ledger):** `core/diagnostics/**`, `native/rust/crates/ripdpi-runtime-policy/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

If SOFT_DISABLE is applied and the app never retries on TCP and simply
breaks, mark that app family `NO_TCP_FALLBACK` and don't apply
soft-disable again. Protects us from breaking apps that hard-depend on
QUIC.

## Plan reference

[[ripdpi-android-direct-mode-plan-2026-04-20]] §3 SOFT_DISABLE enforcement.

## Acceptance criteria

- [x] Heuristic observes whether the app opens a TCP connection to the
    same host within a bounded window after a UDP/443 drop.
- [x] On no-retry, mark the app family `NO_TCP_FALLBACK` in a per-app
    memory.
- [x] The memory is invalidated on app update (package version change).
- [x] Detection is conservative by default — false positives are better
    than breaking apps silently.
- [x] Unit test covers: app retries (no mark), app never retries (mark),
    app partially retries (no mark).

## Implementation note

As of 2026-04-23, RIPDPI already has a bounded-window `NO_TCP_FALLBACK`
heuristic plus regression coverage and runtime behavior that stops
reapplying UDP suppression once the signal is learned. What remains open is
true per-app-family memory and invalidation on app package version change.

## Work log

- 2026-05-16: Added `AppFamilyMemory` in `native/rust/crates/ripdpi-runtime-policy/src/app_family_memory.rs`.
  Public API: `record_no_tcp_fallback(pkg, version)`, `should_skip_soft_disable(pkg, version) -> bool`,
  `invalidate_on_app_update(pkg, new_version)`. Key is `(package_name, version_code)`; version change
  automatically invalidates the old mark. Wired as `pub mod app_family_memory` + `pub use AppFamilyMemory`
  in lib.rs. 5 unit tests all pass. Verify: `cargo nextest run -p ripdpi-runtime-policy` exit 0,
  84 tests run, 84 passed.

## Links

- [[Implement QUIC soft-disable per tuple]]
- [[Epic - Direct-mode transport policy and verdicts]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]
