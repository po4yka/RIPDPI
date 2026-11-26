---
id: DAT-1795704964813672
title: Skip non-surviving bundle entries when allowlist mode is detected
kind: feature
status: backlog
area: data
priority: medium
owner: Android data engineer
parent: null
blocked_by: [DAT-1795085420746769, DGN-1795704952088416]
spec_mode: required
openspec_change: dat-1795704964813672-skip-non-surviving-bundle-entries-when-allowlist-mode-is-detected
created: 2026-11-26
updated: 2026-11-26
status_detail: Waits for the server counterpart to ship the label in the bundle v3 contract and for the allowlist verdict task
---

## Goal

When allowlist mode is detected, failover skips bundle entries whose allowlist-regime survivability label says they do not survive, and treats unlabeled entries as neutral.

## Context

Server counterpart: `po4yka/ripdpi-vpn-deploy#SCR-1795705796387934`. The server adds an optional per-entry allowlist-survivability label to bundle v3. This task consumes it after the bundle v3 consumer (DAT-1795085420746769) lands and after the allowlist verdict (DGN-1795704952088416) exists.

Decision 2026-11-26: amend v3, no v4. Values are `yes`, `no` and `unknown`, the default is `unknown`, and a non-`unknown` value comes only from field evidence. Both repositories vendor the changed contract before either ships v3.

Serialized lane: the vendored contract fixtures and their drift pin are touched by one writer only.

## Acceptance criteria

- The bundle parser reads the survivability label per entry, and golden tests cover labeled, unlabeled and malformed values.
- In allowlist mode, failover ordering excludes entries labeled as not surviving and keeps unlabeled entries neutral.
- Outside allowlist mode, the label does not change ordering.
- The drift pin matches the vendored schema that carries the label.

## Ownership

Not assigned yet. Before a writer starts, record one owner for the vendored bundle contract fixtures and the endpoint-health ordering code that DAT-1795085420746769 changes.
