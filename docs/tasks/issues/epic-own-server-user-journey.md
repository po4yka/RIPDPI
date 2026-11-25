---
id: EPC-1795615697939690
title: Guide new users from install to their own VPN server
kind: epic
status: backlog
area: epic
priority: high
owner: unassigned
parent: null
blocked_by: []
spec_mode: required
openspec_change: epc-1795615697939690-epic-own-server-user-journey
created: 2026-11-25
updated: 2026-11-25
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
