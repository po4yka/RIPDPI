---
id: DGN-1795704952088416
title: Classify mobile allowlist mode as its own diagnostics verdict
kind: feature
status: review
area: diagnostics
priority: medium
owner: Diagnostics engineer
parent: null
blocked_by: []
spec_mode: required
openspec_change: dgn-1795704952088416-classify-mobile-allowlist-mode-as-its-own-diagnostics-verdict
created: 2026-11-26
updated: 2026-11-27
status_detail: 'Merged; local gates and remote CI passed (run 46009834608 on d9aa00b99f). A device run remains: on a network with an allowlist regime (permitted domains reachable, foreign addresses and UDP blocked) diagnostics shows the allowlist verdict and the narrowed transports; on a network where every target fails it does not.'
---

## Goal

Diagnostics reports mobile allowlist mode as its own verdict, separate from a generic outage and from DPI blocking, and narrows the candidate transports for that regime.

## Context

Mobile allowlist mode means that destinations in a permitted set stay reachable while foreign targets and UDP fail. The evidence is a set of observations by one author, with conclusions that the reader inferred. Treat it as unverified.

The observations report address-level filtering outside a permitted set, server-name matching per entry, broad blocking of UDP toward addresses outside the permitted set, and only standard HTTPS completing on permitted segments.

Server counterpart: `po4yka/ripdpi-vpn-deploy#SCR-1795705796387934` (optional per-entry allowlist-survivability label in bundle v3, consumed by DAT-1795704964813672).

The verdict needs a probe set that includes permitted-domain targets, foreign targets and UDP targets on the same network. Universal failure on all targets means offline or a captive state, not allowlist mode.

## Acceptance criteria

- A network where permitted-domain targets are reachable while foreign-address targets and UDP targets fail is classified as allowlist mode, distinct from a generic outage and from a DPI block.
- A network where every target fails is not classified as allowlist mode.
- The allowlist verdict narrows the candidate transports to those that can complete standard HTTPS toward a permitted destination, and the narrowing is visible in the diagnostics result.
- The verdict, its probe evidence and the narrowing reason appear in history and redacted exports without addresses of user networks.

## Ownership

The diagnostics engineer owns the verdict model in `ripdpi-diagnostics-classification` (`classification::mobile_allowlist`). The classification depends on the cohorts and target classes of the selective-availability matrix (DGN-1795704974460888).
