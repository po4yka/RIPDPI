---
id: EPC-1795615697939690
title: Guide new users from install to their own VPN server
kind: epic
status: doing
area: epic
priority: high
owner: unassigned
parent: null
blocked_by: []
spec_mode: required
openspec_change: epc-1795615697939690-epic-own-server-user-journey
created: 2026-11-25
updated: 2026-11-28
status_detail: "Children merged and in review; remote CI passed (run 46009834608 on d9aa00b99f). Open: DGN-1795704789266331 (Require a volume-moving transfer for the connection test verdict) is still in review, each child still lacks its device run, and the clean-install on-device journey to a connected own server against a real test server remains"
---

## Goal

A new user without a preset VPN configuration can find, add, configure, start, monitor, and manage their own server.

## Context

Found in the 2026-11-25 on-device user-flow audit (Pixel 8 Pro, githubDebug at 66673515dd, clean install). Onboarding covers only local DPI mode, the path to add a server is hidden behind the Connection diagram, and the server list, editors, imports, and monitoring have the gaps listed in the child tasks.

## Acceptance criteria

- Every child task of this epic is done or dropped with a recorded reason.
- An on-device journey from a clean install to a connected own server passes against a real test server.

## Ownership

Child tasks own their files. Locale resources and Roborazzi baselines are serialized lanes: at most one child task changes them at a time. The `manage-all-servers-in-one-list` task fixes the server and preset terms; other UI tasks use them.
