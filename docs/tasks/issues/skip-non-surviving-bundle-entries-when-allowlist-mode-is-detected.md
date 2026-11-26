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
status_detail: Waits for the paired server repository to define the label and for the allowlist verdict task
---

## Goal

When allowlist mode is detected, failover skips bundle entries whose allowlist-regime survivability label says they do not survive, and treats unlabeled entries as neutral.

## Context

The paired server repository adds a per-entry allowlist-regime survivability label to ripdpi-bundle. This task consumes it after the bundle v3 consumer (DAT-1795085420746769) lands and after the allowlist verdict (DGN-1795704952088416) exists.

The bundle epic rule is that a new bundle version replaces the old one with no compatibility path. The design decides whether the label is an additive v3 field or a new version, and applies that rule.

Serialized lane: the vendored contract fixtures and their drift pin are touched by one writer only.

## Acceptance criteria

- The bundle parser reads the survivability label per entry, and golden tests cover labeled, unlabeled and malformed values.
- In allowlist mode, failover ordering excludes entries labeled as not surviving and keeps unlabeled entries neutral.
- Outside allowlist mode, the label does not change ordering.
- The drift pin matches the vendored schema that carries the label.

## Ownership

Not assigned yet. Before a writer starts, record one owner for the vendored bundle contract fixtures and the endpoint-health ordering code that DAT-1795085420746769 changes.
