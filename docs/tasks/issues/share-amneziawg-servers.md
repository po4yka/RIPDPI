---
id: UIX-1795661310559744
title: Share AmneziaWG servers as full .conf files
kind: feature
status: backlog
area: ui
priority: medium
owner: unassigned
parent: EPC-1795615697939690
blocked_by: []
spec_mode: required
openspec_change: uix-1795661310559744-share-amneziawg-servers
created: 2026-11-26
updated: 2026-11-26
---

## Goal

The user can share a saved AmneziaWG server as a complete `.conf` file, from the same server actions menu that relay servers use.

## Context

Split from UIX-1795615852980919 (Manage all servers in one list). That change added rename, duplicate, share, and delete actions to every server row, but AmneziaWG rows have no Share action: no exporter turns a saved `AwgActivationRequest` back into a `.conf` file. A file without the AmneziaWG 3.x obfuscation fields (Jc, Jmin, Jmax, S1-S4, H1-H4, I1-I5) connects as plain WireGuard and fails against an AmneziaWG server, so a partial exporter is worse than none.

## Acceptance criteria

- An exporter writes a saved AmneziaWG server as a `.conf` file that carries the interface, peer, and every AmneziaWG 3.x obfuscation field the server has.
- Importing an exported file gives a server equal to the original, for plain WireGuard, AmneziaWG 1.x, and AmneziaWG 3.x servers.
- AmneziaWG rows in the Servers list offer Share in the server actions menu and on long-press, like relay rows.
- Share for a subscription-owned AmneziaWG server follows the same rule as subscription-owned relay servers.

## Ownership

One writer in a dedicated worktree owns the AmneziaWG exporter in `core/data`, its tests, and the AWG row actions in `app/.../connection/ServerListItems.kt` and `ServerListViewModel.kt`.
