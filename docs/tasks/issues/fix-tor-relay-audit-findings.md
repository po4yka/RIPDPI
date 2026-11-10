---
id: RLY-1794300120922878
title: Fix Tor relay audit findings
kind: bug
status: done
area: relay
priority: high
owner: Tor audit team
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-tor-relay-audit
created: 2026-11-10
updated: 2026-11-10
closed_at: "2026-11-10T13:28:55Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: 823 Rust and 106 Kotlin tests passed; strict Clippy, staticAnalysis with one worker, just ci-gates and independent reviews passed. Source repairs were pushed to main at 54e8d32774668d2870a6a82f324a5142a18feb75. Real Tor and Android acceptance remain unclaimed.
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
