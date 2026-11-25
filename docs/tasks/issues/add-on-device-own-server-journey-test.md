---
id: TST-1795615863005942
title: Add an on-device own-server journey test
kind: chore
status: backlog
area: testing
priority: medium
owner: unassigned
parent: EPC-1795615697939690
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-25
updated: 2026-11-25
spec_reason: test-only
---

## Goal

An automated journey proves that a new user can add, connect, observe, and disconnect their own server.

## Context

Found in the 2026-11-25 on-device user-flow audit (Pixel 8 Pro, githubDebug at 66673515dd, clean install). The audit could not verify a successful relay connection or its monitoring, because it used unreachable test addresses. It also did not cover paste from a real clipboard, the Advanced persona, Proxy-only mode, file import, or subscription refresh.

## Acceptance criteria

- A Kaspresso or local-acceptance journey goes from a clean install to a connected VLESS and AmneziaWG server on a local test endpoint, checks Status, and disconnects.
- The journey covers paste link with a real clipboard and the Proxy-only mode.
- The journey runs on the local emulator pool and its result is recorded.

## Ownership

One writer in a dedicated worktree owns `app/src/androidTest/` journeys and local test fixtures.
