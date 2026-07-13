---
title: "Schedule diagnostics retention without monitor"
type: task
status: review
area: diagnostics
priority: critical
owner: Codex
parent: epic-fix-android-critical-residual-risks
blocks: []
blocked_by: []
created: 2026-07-13
updated: 2026-07-13
---

## Goal

Apply configured diagnostics retention even when monitoring is disabled and the VPN is stopped.

## Acceptance criteria

- A test proves expired public IP, event, and report data is removed without starting the monitor or VPN.
- Cleanup is durable, bounded, and scheduled independently of sampling.
- Diagnostics unit tests pass.

## Work log

- Added unique daily WorkManager retention independent of sampling, monitor settings, and VPN service state.
- The worker reads the current configured retention at execution time and retries transient failures.
- Added a lifecycle-independent retention invocation test.
