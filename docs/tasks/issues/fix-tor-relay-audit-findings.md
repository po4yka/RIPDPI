---
id: RLY-1794300120922878
title: Fix Tor relay audit findings
kind: bug
status: review
area: relay
priority: high
owner: Tor audit team
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-tor-relay-audit
created: 2026-11-10
updated: 2026-11-10
---

## Goal

Fix all six confirmed Tor relay audit defects.

## Acceptance criteria

Regression tests cover DNS privacy, dial budgets, bootstrap retry, PT shutdown, profile state isolation, and draft validation. Independent review and applicable local gates pass before integration.

## Ownership

- fix_dns_timeout: proxy-config, proxy-runtime, session and runtime-adapter; F1/F2.
- fix_arti_lifecycle: ripdpi-tor, Tor transport owner, vendored Arti; F3/F4. Sole Cargo.toml/Cargo.lock writer.
- fix_tor_profiles: Kotlin path provider, Tor bridge validation and tests; F5/F6. Sole locale writer if needed.
- Parent: planning records, combined validation and integration. All writers use separate worktrees.
