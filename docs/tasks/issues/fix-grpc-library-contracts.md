---
id: TRN-1794371438573712
title: Fix gRPC library stream contracts
kind: bug
status: doing
area: transport
priority: high
owner: Transport maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-grpc-library-contracts
created: 2026-11-11
updated: 2026-11-11
---

## Goal

Fix the four TRN-05 library contract findings without adding an application transport.

## Acceptance criteria

All four regression groups pass, independent review has no unresolved findings, and offline CI source gates pass.

## Ownership

The primary writer owns ripdpi-xhttp and these planning records. Subagents are read-only reviewers. No parallel writer owns shared files.
