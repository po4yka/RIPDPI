---
id: VPN-1795271597027505
title: Schedule bounded selector probes after network changes and failures
kind: feature
status: review
area: vpn
priority: high
owner: VPN runtime engineering
parent: null
blocked_by: []
spec_mode: required
openspec_change: selector-recovery-probes
created: 2026-11-21
updated: 2026-11-21
---

## Goal

Check the active selector route soon after a failure or network change, with a shared traffic limit.

## Acceptance criteria

- A burst of runtime or physical network events causes at most one recovery pass after the network has time to settle.
- Recovery checks use the current route, existing failure thresholds, and the shared durable payload budget.
- A budget rejection stays deferred and does not add a transport failure.
