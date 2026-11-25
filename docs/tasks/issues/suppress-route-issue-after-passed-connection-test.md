---
id: SVC-1795615846684850
title: Stop showing Route issue right after a passed connection test
kind: bug
status: backlog
area: service
priority: high
owner: unassigned
parent: EPC-1795615697939690
blocked_by: []
spec_mode: required
openspec_change: svc-1795615846684850-suppress-route-issue-after-passed-connection-test
created: 2026-11-25
updated: 2026-11-25
---

## Goal

A new user who passes the onboarding connection test sees a healthy connection on the home screen, not a route warning caused by start-up noise.

## Context

Found in the 2026-11-25 on-device user-flow audit (Pixel 8 Pro, githubDebug at 66673515dd, clean install). Onboarding reports `Connection test: Passed`, and the home screen immediately shows `Connected · Route issue`. Logcat shows four instant `ConnectionRefused` upstream connects (elapsed 0 ms) in the first seconds. The warning clears by itself after about one minute. It reproduced on two clean connections, including after `pm clear`.

## Acceptance criteria

- A regression test feeds start-up connection refusals like the observed ones and the route health stays healthy.
- A persistent route fault still shows Route issue within the existing detection window.
- On a device, finishing onboarding with a passed test shows Connected without Route issue.

## Ownership

One writer in a dedicated worktree owns route-health classification in `core/service` and its tests.
