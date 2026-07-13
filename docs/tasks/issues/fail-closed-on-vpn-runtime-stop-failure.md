---
title: "Fail closed on VPN runtime stop failure"
type: task
status: review
area: vpn
priority: critical
owner: Codex
parent: epic-fix-android-critical-residual-risks
blocks: []
blocked_by: []
created: 2026-07-13
updated: 2026-07-13
---

## Goal

Do not publish `Disconnected`, call `stopSelf`, or remove socket protection while a runtime owner may still be alive.

## Acceptance criteria

- Fault injection reproduces a runtime stop failure.
- Failure state remains observable and protect cleanup is deferred until runtime termination is confirmed.
- `:core:service:testDebugUnitTest` passes.

## Work log

- Stop failures now leave the lifecycle in `STOPPING`, retain the registered runtime, publish `Failed`, and propagate the error without `Disconnected` or `stopSelf`.
- VPN revoke keeps JNI and Unix socket protection registered when runtime termination fails.
- Added fault-injection coverage for coordinator state and protection ordering.
