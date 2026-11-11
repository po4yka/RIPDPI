---
id: TRN-1794371438573712
title: Fix gRPC library stream contracts
kind: bug
status: done
area: transport
priority: high
owner: Transport maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-grpc-library-contracts
created: 2026-11-11
updated: 2026-11-11
closed_at: "2026-11-11T04:52:26Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Four isolated fixes; 102 crate tests, Clippy, final ci-gates and locked metadata passed at 002c15238d; independent general and async reviews clear.
---

## Goal

Fix the four TRN-05 library contract findings without adding an application transport.

## Acceptance criteria

All four regression groups pass, independent review has no unresolved findings, and offline CI source gates pass.

## Ownership

The primary writer owns ripdpi-xhttp and these planning records. Subagents are read-only reviewers. No parallel writer owns shared files.
