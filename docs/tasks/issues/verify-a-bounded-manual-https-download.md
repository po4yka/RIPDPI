---
id: DGN-1795320365568134
title: Verify a bounded manual HTTPS download
kind: feature
status: review
area: diagnostics
priority: high
owner: Diagnostics engineering
parent: null
blocked_by: []
spec_mode: required
openspec_change: verified-diagnostic-download
created: 2026-11-22
updated: 2026-11-22
---

## Goal

Run a bounded diagnostic download against an operator-controlled HTTPS endpoint and report whether the received body matches the expected size and SHA-256.

## Acceptance criteria

- A user can supply the HTTPS URL, expected byte count, and SHA-256 from the manual scan screen.
- A complete match, idle stall, early EOF, and hash mismatch produce distinct results.
- The scan retains capped progress and runs once after a user action.

## Ownership

- The UI writer owns app diagnostics source, UI tests, locales, and their generated translation export.
- The diagnostics writer owns core/diagnostics, core catalog integration, and the bundled profile and its shared fixture.
- The integration writer owns Rust, verification, and Git integration.
