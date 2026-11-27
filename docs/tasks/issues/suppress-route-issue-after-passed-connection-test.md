---
id: SVC-1795615846684850
title: Stop showing Route issue right after a passed connection test
kind: bug
status: done
area: service
priority: high
owner: route-issue
parent: EPC-1795615697939690
blocked_by: []
spec_mode: required
openspec_change: svc-1795615846684850-suppress-route-issue-after-passed-connection-test
created: 2026-11-25
updated: 2026-11-27
closed_at: "2026-11-27T14:33:51Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Route-issue fix verified by telemetry adapter tests, on device (Pixel 8 Pro audit pattern reproduced, no Route issue) and in main CI run 46009834608 on d9aa00b99f.
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
