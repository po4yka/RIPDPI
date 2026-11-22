---
id: OUT-1795326656806182
title: Prefer selector probe evidence over cloud down health
kind: bug
status: review
area: outbound
priority: high
owner: Outbound
parent: null
blocked_by: []
spec_mode: required
openspec_change: selector-cloud-health
created: 2026-11-22
updated: 2026-11-22
---

## Goal

Keep testing the current selector member when the bundle reports it down. When ordinary candidates fail on the current path, let one eligible down reserve use the existing probe budget.

## Acceptance criteria

- The selected automatic member is measured despite fresh cloud down health.
- The selector admits one eligible down reserve after ordinary failures. It uses the same payload budget and confirmation rules.
- Budget denial, an undecided result, and an excluded candidate cannot start a down reserve probe.
