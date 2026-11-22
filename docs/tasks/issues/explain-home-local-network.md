---
id: DGN-1795354722978192
title: Explain local network conditions in Home diagnostics
kind: feature
status: review
area: diagnostics
priority: medium
owner: Diagnostics maintenance
parent: null
blocked_by: []
spec_mode: required
openspec_change: home-local-network-diagnostics
created: 2026-11-22
updated: 2026-11-22
---

## Goal

Home diagnostics explain when a physical network requires sign-in or Android has not validated its Internet access.

## Acceptance criteria

- Show sign-in and repeat instructions for a confirmed portal.
- Show unvalidated Internet access separately.
- Treat validated and unknown states without a warning.
- Discard current-network guidance when the run loses its original network scope.
