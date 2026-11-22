---
id: DGN-1795351267598237
title: Preserve ECH comparison failure reasons
kind: bug
status: review
area: diagnostics
priority: medium
owner: Diagnostics maintenance
parent: null
blocked_by: []
spec_mode: required
openspec_change: ech-comparison-failure-reasons
created: 2026-11-22
updated: 2026-11-22
---

## Goal

Identify why real ECH fails in the comparison card, scan history, and redacted archive.

## Acceptance criteria

- Report absent, failed lookup, unusable configuration, and authenticated rejection as distinct results.
- Accept a compatible HTTPS record when its explicit port matches the endpoint.
- Keep one attempt per mode and preserve post-handshake transfer results.
