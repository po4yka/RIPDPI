---
id: UIX-1795615862148383
title: Show the selected relay on Status after a relay failure
kind: bug
status: review
area: ui
priority: medium
owner: unassigned
parent: EPC-1795615697939690
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-25
updated: 2026-11-26
spec_reason: regression-tested-single-module
status_detail: "Merged; local gates and remote CI passed (run 46009834608 on d9aa00b99f); a device run remains: start a relay that fails (for example VLESS) and check that Status names the selected relay and not Direct, and that the Relay server row opens the editor of the selected server."
---

## Goal

The Status card and the Relay server row always describe the server the user selected.

## Context

Found in the 2026-11-25 on-device user-flow audit (Pixel 8 Pro, githubDebug at 66673515dd, clean install). After a VLESS start failure, the Status card says `Whole device · Direct` and `tcp: split(host+1)` although Relay is selected. The `Relay server` row opens the general Manual profile editor instead of the server editor.

## Acceptance criteria

- After a relay failure, the Status card shows the selected relay and its failed state; a test covers it.
- The Relay server row opens the editor of the selected server.

## Ownership

One writer in a dedicated worktree owns Status and Connection presentation in `app` and their tests.
