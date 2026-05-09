---
title: Split VPN protect socket server responsibilities
type: task
status: backlog
area: service
priority: medium
owner: unassigned
parent: epic-post-refactor-architecture-cleanup
blocks: []
blocked_by: []
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Split VPN protect socket server responsibilities #repo/RIPDPI #area/service #status/backlog 🔼

## Summary

Split `core/service/src/main/kotlin/com/poyka/ripdpi/services/VpnProtectSocketServer.kt` so Unix socket lifecycle, fd extraction/protection, failure mapping, client sessions, and dispatcher/backpressure logic are reviewed separately.

## Context

The current server module binds and cleans up the socket path, accepts clients, reads fd-passing handshakes, calls `VpnService.protect`, maps failures, closes fds, and owns the session dispatcher.

## Acceptance criteria

- [ ] Extract socket binding, accept loop, and filesystem cleanup into a server lifecycle module.
- [ ] Extract client session handshake and fd extraction.
- [ ] Extract fd protection result/failure mapping.
- [ ] Extract dispatcher/backpressure handling.
- [ ] Preserve current protect failure reporting and shutdown behavior.

## Completion outcome

Closing this task means VPN protect over Unix sockets has separate lifecycle, accept/session, fd extraction/protection, failure reporting, and dispatcher modules. Low-level socket mechanics should no longer share a file with policy/error reporting.

## Regression guardrails

- Do not leave fd ownership, socket cleanup, and failure telemetry in one class.
- Do not duplicate fd-close behavior across modules without a single ownership rule.
- Do not let dispatcher/backpressure changes touch the Android `VpnService.protect` result mapper.
- Do not close the task unless shutdown cleanup and failure reporting behavior are covered.
- Do not close the task without focused unit tests for extracted lifecycle, session, fd-protection, and failure-mapping components where test seams are available.

## Links

- [[Epic - Post-refactor architecture cleanup]]
