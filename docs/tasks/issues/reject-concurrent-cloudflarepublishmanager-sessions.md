---
title: Reject concurrent CloudflarePublishManager sessions
type: task
status: backlog
area: relay
priority: high
owner: unassigned
parent: epic-cloudflare-publish-hardening
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-20
---

- [ ] #task Reject concurrent CloudflarePublishManager sessions #repo/RIPDPI #area/relay #status/backlog ⏫

## Summary

`CloudflarePublishManager.start()` does not clearly reject an already-running
session — overlap / reentry is possible.

## Audit citation

- `core/service/.../CloudflarePublishRuntime.kt:175-181,183-247`

## Acceptance criteria

- [ ] `start()` returns a typed error (`AlreadyRunning`) when invoked on a
    running session.
- [ ] State transitions are covered by a state machine or explicit guard.
- [ ] Unit test exercises concurrent `start()` calls.

## Links

- [[Epic - Cloudflare publish hardening]]
- [[ripdpi-android-audit-2026-04-20]]


## composable-transport-layer-parity
