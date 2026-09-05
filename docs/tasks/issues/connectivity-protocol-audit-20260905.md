---
id: DGN-1788582590436769
title: Correct connectivity diagnostics and protocol configuration
kind: bug
status: done
area: diagnostics
priority: high
owner: Primary audit writer
parent: null
blocked_by: []
spec_mode: required
openspec_change: connectivity-protocol-audit-20260905
created: 2026-09-05
updated: 2026-09-05
closed_at: "2026-09-05T07:49:53Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: 28 corrected finding groups; exact application SHA a0986d2e7495c0cbefd6e47781f8b9e16cdaae5d passed CI 33950376859 with 5403 Rust and 9044 JVM executions, five Android APIs, real TUN acceptance, full static analysis and release checks. Coverage limits are in the audit report.
---

## Goal

Correct the confirmed connectivity, protocol and settings failures found in the project audit.

## Acceptance criteria

All requirements in connectivity-protocol-audit-20260905 have regression evidence. The combined tree passes relevant gates, preserves user work, and has an audit report with coverage limits. Integration and push are explicitly authorized by the user.

## Ownership

See the change design for the three isolated writer lanes. The primary writer alone owns task/spec/report files, DNS resolver and all serialized shared files. No dependencies, schemas, locales, goldens or baselines are to change without primary review.
