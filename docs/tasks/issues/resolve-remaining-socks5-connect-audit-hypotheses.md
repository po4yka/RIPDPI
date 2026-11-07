---
id: PRX-1794074402219640
title: Resolve remaining SOCKS5 CONNECT audit hypotheses
kind: bug
status: doing
area: proxy
priority: high
owner: Proxy runtime maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: resolve-socks5-connect-hypotheses
created: 2026-11-07
updated: 2026-11-07
---

## Goal

Resolve all four remaining LOC-01 hypotheses with local evidence and fix only confirmed contract defects.

## Acceptance criteria

Each confirmed defect has its own commit and regression test. Record all hypothesis verdicts and unrun limits. Independent review, affected Rust tests, and ci-gates pass before main integration and push.
