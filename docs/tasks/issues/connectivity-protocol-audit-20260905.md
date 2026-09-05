---
id: DGN-1788582590436769
title: Correct connectivity diagnostics and protocol configuration
kind: bug
status: doing
area: diagnostics
priority: high
owner: Primary audit writer
parent: null
blocked_by: []
spec_mode: required
openspec_change: connectivity-protocol-audit-20260905
created: 2026-09-05
updated: 2026-09-05
---

## Goal

Correct the confirmed connectivity, protocol and settings failures found in the project audit.

## Acceptance criteria

All requirements in connectivity-protocol-audit-20260905 have regression evidence. The combined tree passes relevant gates, preserves user work, and has an audit report with coverage limits. Integration and push are explicitly authorized by the user.

## Ownership

See the change design for the three isolated writer lanes. The primary writer alone owns task/spec/report files, DNS resolver and all serialized shared files. No dependencies, schemas, locales, goldens or baselines are to change without primary review.
