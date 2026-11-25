---
id: UIX-1795615862353491
title: Show the profile name instead of its internal ID
kind: bug
status: backlog
area: ui
priority: high
owner: unassigned
parent: EPC-1795615697939690
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-25
updated: 2026-11-25
spec_reason: regression-tested-single-module
---

## Goal

Every server list and card shows the name the user or the link gave the server.

## Context

Found in the 2026-11-25 on-device user-flow audit (Pixel 8 Pro, githubDebug at 66673515dd, clean install). A link imported as `#Test%20VLESS%20Reality` shows the internal ID (`de53ebe8-...`) in the Servers sheet and the Connection card. Subscription servers show `Server 1: www.example.com`, the SNI, instead of the name.

## Acceptance criteria

- The Servers sheet, the Connection card, and subscription servers show the profile name; a test covers each surface.
- A profile without a name shows the kind and host, never the internal ID.

## Ownership

One writer in a dedicated worktree owns server display mapping in `app` and its tests.
