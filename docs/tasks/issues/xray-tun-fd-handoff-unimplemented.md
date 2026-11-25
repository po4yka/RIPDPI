---
id: ENG-1795610462793424
title: Implement or remove the LibXraySetTunFd handoff
kind: chore
status: backlog
area: engine
priority: low
owner: comment-ratchet
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-25
updated: 2026-11-25
spec_reason: mechanical-refactor
---

## Goal

`core/engine-api/src/main/kotlin/com/poyka/ripdpi/core/XrayTunnelHandoff.kt#XrayTunnelHandoff` declares the `LibXraySetTunFd` handoff, but the code throws when it is selected. A deleted source comment said that it is not implemented.

## Acceptance criteria

`LibXraySetTunFd` either hands the TUN file descriptor to libXray with a test that proves it, or the value is removed so that no caller can select it.
