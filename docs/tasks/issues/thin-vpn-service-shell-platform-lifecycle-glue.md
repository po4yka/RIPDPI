---
title: Thin VPN service shell platform lifecycle glue
type: task
status: backlog
area: service
priority: high
owner: unassigned
parent: epic-finish-srp-residual-architecture-debt
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Thin VPN service shell platform lifecycle glue #repo/RIPDPI #area/service #status/backlog ⏫

## Summary

`RipDpiVpnService` still creates the session component, starts/stops the protect
socket server, registers proxy and WARP VPN protect JNI callbacks, delegates
service start/stop/revoke, updates foreground notifications, and manages
underlying-network binding. Keep the Android service shell thin by moving
platform lifecycle, JNI protection, notification rendering, and VPN network
binding behind focused collaborators.

## Audit citation

- `core/service/src/main/kotlin/com/poyka/ripdpi/services/RipDpiVpnService.kt` lines 61-127.

## Scope

- In scope: Android service shell responsibilities, protect socket lifecycle,
  JNI protect callback registration, foreground notification updates, and
  underlying network binding.
- Out of scope: changing VPN tunnel behavior or service permissions.

## Acceptance criteria

- [ ] `RipDpiVpnService` primarily delegates Android lifecycle events to
    focused owners.
- [ ] Protect socket and JNI protect callback lifecycle move behind a dedicated
    platform-protection owner.
- [ ] Notification rendering is separated from runtime/session composition.
- [ ] Underlying-network binding has a focused lifecycle owner and tests.

## Links

- [[Epic - Finish SRP residual architecture debt]]
