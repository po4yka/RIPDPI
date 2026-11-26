---
id: UIX-1795677614058050
title: Rename imported servers by their name, not their internal ID
kind: bug
status: backlog
area: ui
priority: medium
owner: unassigned
parent: EPC-1795615697939690
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-26
updated: 2026-11-26
spec_reason: regression-tested-single-module
---

## Goal

Renaming a link or subscription server changes the name the user sees, starts from that name, and works for servers inside a group.

## Context

Found while integrating UIX-1795615862353491 (server name instead of ID). The Servers sheet shows a link or subscription server by its group member name, but `RenameServerDialog` in `ServerListItems.kt` prefills `server.id`, the generated UUID. `ServerListViewModel.rename` calls `renameRelay`, which changes the relay profile ID and not the display name. That ID belongs to a proxy group member, so `requireRelayUnreferenced` rejects the rename with `RelayProfileInUseException`. The user sees a UUID in the field and then an error.

## Acceptance criteria

- The rename dialog for a link or subscription server opens with the name the list shows, never the internal ID; a test covers it.
- Renaming such a server changes the name shown in the Servers sheet and the Connection card and keeps the server in its group; a test covers it.
- Renaming an own server and an AmneziaWG server works as before.

## Ownership

One writer in a dedicated worktree owns the rename path in `app` (`ServerListItems.kt`, `ServerListViewModel.kt`) and the group member name update in `core/data`, with their tests.
