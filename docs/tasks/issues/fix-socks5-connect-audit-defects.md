---
id: PRX-1794071497680636
title: Fix SOCKS5 CONNECT audit defects
kind: bug
status: review
area: proxy
priority: high
owner: Proxy runtime maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-socks5-connect-contracts
created: 2026-11-07
updated: 2026-11-07
---

## Goal

Fix the five confirmed LOC-01 SOCKS5 CONNECT defects without expanding protocol scope.

## Acceptance criteria

Each defect has a separate commit and a regression test. Targeted Rust tests, independent review, and ci-gates pass before integration into main. Record unresolved audit hypotheses separately.
