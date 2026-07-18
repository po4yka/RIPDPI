---
title: Restore the VPN after unexpected app process death
type: task
status: doing
area: vpn
priority: high
owner: VPN process-death recovery lane
parent: epic-fail-closed-android-vpn-policy-engine
blocks: []
blocked_by: []
created: 2026-07-18
updated: 2026-07-18
---

## Problem

On a physical Pixel 7 running Android 17, killing the connected Simple-flavor
process with `SIGKILL` removes the VPN. Android recreates the process and foreground
activity, but the VPN service is not reconstructed within 47 seconds even though the
persisted desired state still requests a running VPN.

## Acceptance criteria

- An unexpected process death reconstructs the previously connected VPN without a
  manual Connect action when VPN consent remains valid.
- An explicit user stop remains stopped and is not mistaken for crash recovery.
- Regression tests cover the process-recreation decision and its fail-closed paths.
- A physical Pixel replay observes a new app PID, a connected Android VPN network,
  and successful HTTPS traffic from a separate UID.

## Ownership

This task owns the process-recreation trigger and its focused tests. It does not own
relay selection, bundle contents, locale resources, wire schemas, or native code.
