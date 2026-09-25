---
id: DGN-1790329640901759
title: Show real local packet captures
kind: bug
status: review
area: diagnostics
priority: high
owner: PCAP agent
parent: null
blocked_by: []
spec_mode: required
openspec_change: show-real-local-packet-captures
created: 2026-09-25
updated: 2026-09-25
---

## Goal

The packet capture list and viewer show files and packet bytes recorded on this device, instead of demonstration data.

## Acceptance criteria

- Opening the capture list enumerates completed local captures and shows an empty state when none exist.
- Selecting a capture opens that file; packets shown in the viewer come from its bytes.
- A missing, invalid, or unreadable selection cannot open another private file and shows an error state.
- Targeted app tests cover packet conversion and selected-file confinement.

## Ownership

PCAP agent owns the PCAP routes, viewer presentation, and their tests in its dedicated worktree. The main coordinator owns integration and the generated board. `RipDpiNavHost.kt` and `Route.kt` are edited only in this PCAP worktree before integration.
