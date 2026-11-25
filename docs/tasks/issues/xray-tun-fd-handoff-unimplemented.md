---
id: ENG-1795610462793424
title: Implement or remove the LibXraySetTunFd handoff
kind: chore
status: review
area: engine
priority: low
owner: comment-ratchet
parent: null
blocked_by: []
spec_mode: required
openspec_change: eng-1795610462793424-remove-xray-tun-fd-handoff
created: 2026-11-25
updated: 2026-11-25
---

## Goal

`core/engine-api/src/main/kotlin/com/poyka/ripdpi/core/XrayTunnelHandoff.kt#XrayTunnelHandoff` declares the `LibXraySetTunFd` handoff, but the code throws when it is selected. A deleted source comment said that it is not implemented.

## Acceptance criteria

`LibXraySetTunFd` either hands the TUN file descriptor to libXray with a test that proves it, or the value is removed so that no caller can select it.
