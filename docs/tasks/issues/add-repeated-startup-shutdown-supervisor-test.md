---
title: Add repeated startup-shutdown supervisor test
type: task
status: backlog
area: testing
priority: medium
owner: unassigned
parent: epic-orchestration-test-posture
blocks: []
blocked_by: [UNRESOLVED-POY-129]
created: 2026-04-20
updated: 2026-04-20
---

- [ ] #task Add repeated startup-shutdown supervisor test #repo/RIPDPI #area/testing #status/backlog 🔼

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
