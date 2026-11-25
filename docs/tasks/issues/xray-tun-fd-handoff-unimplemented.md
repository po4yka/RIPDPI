---
id: ENG-1795610462793424
title: Implement or remove the LibXraySetTunFd handoff
kind: chore
status: done
area: engine
priority: low
owner: comment-ratchet
parent: null
blocked_by: []
spec_mode: required
openspec_change: eng-1795610462793424-remove-xray-tun-fd-handoff
created: 2026-11-25
updated: 2026-11-25
closed_at: "2026-11-25T14:53:47Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Removed the unsupported Xray handoff. 5337 JVM tests and Ktlint passed for the affected modules. Verified default and custom loopback ports, tunnel DNS ownership, invalid port rejection before startup, and the diagnostic topology label.
---

## Goal

`core/engine-api/src/main/kotlin/com/poyka/ripdpi/core/XrayTunnelHandoff.kt#XrayTunnelHandoff` declares the `LibXraySetTunFd` handoff, but the code throws when it is selected. A deleted source comment said that it is not implemented.

## Acceptance criteria

`LibXraySetTunFd` either hands the TUN file descriptor to libXray with a test that proves it, or the value is removed so that no caller can select it.
