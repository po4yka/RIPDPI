---
id: SVC-1795615848338894
title: Keep the working tunnel when a new server fails to start
kind: bug
status: review
area: service
priority: high
owner: tunnel-fallback
parent: EPC-1795615697939690
blocked_by: []
spec_mode: required
openspec_change: svc-1795615848338894-keep-active-tunnel-when-new-server-fails
created: 2026-11-25
updated: 2026-11-25
---

## Goal

A failed attempt to connect to a new server does not leave the user without the connection they had, and the error tells them what to check.

## Context

Found in the 2026-11-25 on-device user-flow audit (Pixel 8 Pro, githubDebug at 66673515dd, clean install). With the DPI tunnel connected, Connect on an unreachable AmneziaWG profile shows `Failed to start VPN: AmneziaWG readiness timed out state=idle` and the device is left with no VPN and no fallback. The relay case shows a clear `Relay server could not be reached` but also drops the working tunnel. No notification is posted. The fallback policy between relay servers and AmneziaWG is a separate decision in SVC-1795015549149698.

## Acceptance criteria

- When a server switch fails, the service restores the previous working mode, or the UI states that the device is now unprotected and offers to restore it.
- An AmneziaWG readiness timeout shows a user message that names the likely causes (endpoint, keys, port) and a next step; the raw state stays in diagnostics only.
- A start failure while the app is in the background posts a notification.
- Service lifecycle tests cover a failed switch from a running tunnel.

## Ownership

One writer in a dedicated worktree owns VPN start and switch coordination in `core/service`, the error mapping in `app`, and their tests.
