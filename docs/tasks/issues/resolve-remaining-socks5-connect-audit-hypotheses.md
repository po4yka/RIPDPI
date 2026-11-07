---
id: PRX-1794074402219640
title: Resolve remaining SOCKS5 CONNECT audit hypotheses
kind: bug
status: done
area: proxy
priority: high
owner: Proxy runtime maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: resolve-socks5-connect-hypotheses
created: 2026-11-07
updated: 2026-11-07
closed_at: "2026-11-07T18:27:43Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: 445 local tests passed on explicit Rust1.98.1 at b278654715a2401457d5e9ea01efd1cc821ebdfe; four independently reviewed fixes; pinned ci-gates and architecture/metadata passed; hypothesis limits recorded
---

## Goal

Resolve all four remaining LOC-01 hypotheses with local evidence and fix only confirmed contract defects.

## Acceptance criteria

Each confirmed defect has its own commit and regression test. Record all hypothesis verdicts and unrun limits. Independent review, affected Rust tests, and ci-gates pass before main integration and push.
