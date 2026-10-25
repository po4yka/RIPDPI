---
id: TST-1792893938139528
title: Verify all eight AWG peer acceptance sessions
kind: bug
status: review
area: testing
priority: medium
owner: Transport acceptance
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-25
updated: 2026-10-25
spec_reason: test-only
status_detail: PR 665 merged; exact main native acceptance and CI passed.
---

## Goal

Accept the complete independent AWG 3.1 peer matrix and reject incomplete or
misidentified runs. This test-only repair gates AWG range and signature acceptance.

## Acceptance criteria

- The peer version and revision match the pinned AWG 3.1 fixture and upstream tag.
- Acceptance requires all eight MTU/session pairs, one exact successful Rust test
  in each pair, the correct peer identity, and successful owned-process cleanup.
- Missing, duplicate, failed, ignored, extra or unpinned sessions fail regression
  controls. Other peer adapters still require exactly one test execution.
- All eight real independent-peer sessions, the complete native peer acceptance
  profile, PR CI and final main CI pass. Synthetic controls are not protocol proof.
- The separate PR is merged to main before the task is closed.
