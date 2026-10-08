---
id: DAT-1791477854155125
title: Preserve AWG subscription profile identity during refresh
kind: bug
status: review
area: data
priority: high
owner: awg-refresh
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-awg-subscription-refresh
created: 2026-10-08
updated: 2026-10-08
---

## Goal

Refresh each subscription-owned AWG profile in place and retain its local private key.

## Acceptance criteria

- Repeated refresh retains one opaque profile ID.
- Endpoint, PSK, and cohort changes reach the existing row; a server placeholder retains the local private key.
- Different subscriptions and manual profiles remain isolated.
- Repository and refresh regression tests pass.

## Ownership

The awg-refresh writer owns AWG repository persistence, subscription refresh, bootstrap association, and related tests in the dedicated fix/awg-subscription-refresh worktree. No schema, locale, golden, dependency, or baseline file changes. The parent serializes integration and board regeneration with the mux writer.
