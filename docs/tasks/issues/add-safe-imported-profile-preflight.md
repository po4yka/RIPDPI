---
id: RLY-1786618247484998
title: Add safe imported profile preflight
kind: feature
status: doing
area: relay
priority: high
risk: high
owner: codex
parent: null
blocked_by: []
spec_mode: required
openspec_change: add-safe-imported-profile-preflight
created: 2026-08-13
updated: 2026-08-13
related_tasks: []
---

## Goal

Let a user test an imported relay profile from the import-confirmation screen without saving, selecting, or activating it, while keeping active VPN/proxy sessions isolated from the check.

## Acceptance criteria

- The import-confirmation screen offers a localized, accessible `Check profile` action for relay-activatable profiles.
- A check starts one isolated ephemeral relay runtime and one bounded TCP egress probe without mutating profile groups, relay stores, credentials, active settings, failover memory, or the running service.
- The check fails closed while VPN/proxy lifecycle work is active and always stops and joins its temporary runtime after success, failure, timeout, cancellation, or screen disposal.
- Results distinguish unsupported input, busy service, startup/readiness failure, probe failure, timeout, and success without exposing secrets or claiming that the VPN is validated.
- Targeted unit, Compose, locale/lint, static-analysis, architecture, and physical-device relay checks pass with observed cleanup evidence.
