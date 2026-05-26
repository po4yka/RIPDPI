---
title: Decouple JNI handle-lifetime and telemetry locking
type: task
status: backlog
area: service
priority: medium
owner: Principal Android Rust Architect
parent: epic-runtime-lifecycle-and-supervisors
blocks: [select-resolver-mapping-from-dns-classification, adopt-handlereservation-primitive-in-ripdpiwarp, adopt-handlereservation-primitive-in-tun2sockstunnel, adopt-handlereservation-primitive-in-networkdiagnostics-cancelscan-latency]
blocked_by: []
created: 2026-04-20
updated: 2026-05-16
---

- [ ] #task Decouple JNI handle-lifetime and telemetry locking #repo/RIPDPI #area/service #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `decouple-jni-handle-lifetime-and-telemetry-locking`
- **Verify:** `just test-module core:engine`
- **Scope (only modify these + this file + the ledger):** `core/engine/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

`RipDpiProxy` and `RipDpiRelay` serialize all handle-sensitive JNI work behind a single mutex. Telemetry polls head-of-line-block lifecycle calls and vice versa.

## Audit citation

- `core/engine/.../RipDpiProxy.kt:132-142,220-254,267-277`
- `core/engine/.../RipDpiRelay.kt:192-318`

## Acceptance criteria

- [ ] Separate locks: one for handle create/destroy (lifetime), one for ordinary telemetry/config updates against a live handle.
- [ ] Telemetry calls no longer block lifecycle operations (measured).
- [ ] Lifetime transitions remain serialized against all other handle use.
- [ ] No new correctness regressions in existing tests.

## Links

- [[Epic - Runtime lifecycle and supervisors]]
- [[Add native readiness events to RipDpi wrappers]]
- [[ripdpi-android-audit-2026-04-20]]

## Work log

- 2026-05-16: Dropped orphaned blocker reference 'surface-typed-cache-degradation-reasons' (file does not exist); reclassified to backlog.
