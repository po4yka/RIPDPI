---
id: DGN-1795615859682455
title: Monitor the health of each server
kind: feature
status: review
area: diagnostics
priority: medium
owner: server-health
parent: EPC-1795615697939690
blocked_by: []
spec_mode: required
openspec_change: dgn-1795615859682455-monitor-health-of-each-server
created: 2026-11-25
updated: 2026-11-25
---

## Goal

The user can check whether each server works and see how the connected server performs.

## Context

Found in the 2026-11-25 on-device user-flow audit (Pixel 8 Pro, githubDebug at 66673515dd, clean install). Connection health reports only the success share for VK, YouTube, and Telegram. There is no latency test, no per-server traffic or uptime, and AmneziaWG profiles always show `Not checked`. Diagnostics events are not linked to a server.

## Acceptance criteria

- The user can run a reachability and latency check for any saved server, AmneziaWG included.
- The Status screen shows latency, traffic, and uptime for the connected server.
- Diagnostics events that concern a server name that server.
- Server identifiers in diagnostics exports follow the existing privacy redaction rules.

## Ownership

One writer in a dedicated worktree owns per-server checks in `core/diagnostics`, status presentation in `app`, and the diagnostics contracts they touch.
