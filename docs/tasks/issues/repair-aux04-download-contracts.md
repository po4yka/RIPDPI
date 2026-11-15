---
id: SVC-1794755640074922
title: Repair AUX-04 catalog download contracts
kind: bug
status: doing
area: service
priority: high
owner: integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-aux04-download-contracts
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Repair the four confirmed AUX-04 defects: enforce download limits before buffering, reject incomplete TLS bodies, cancel owned HTTP requests, and preserve response media types.

## Acceptance criteria

- Each finding has a separate code commit and a regression that observes the repaired behavior.
- Required source, Kotlin, Rust, JNI, and static analysis gates pass.
- Independent review has no unresolved in-scope defects.
- Main contains the fixes and origin/main receives the exact integrated commits.
- Device and external compatibility gaps remain explicit.

## Ownership

- limits-worker owns F1: the three Retrofit download services, one shared bounded response helper, and their tests.
- closure-worker owns F2: native fetch TLS stream/completeness handling and closure tests.
- bridge-worker owns F3 then F4 as separate commits: HTTP cancellation registry/executor/JNI/Kotlin bridge and Content-Type response propagation.
- Integration owns this portfolio record, OpenSpec artifacts, combined validation, review, API snapshots, and integration.
- Workers use separate worktrees. No worker edits dependencies, locale sets, baselines, or golden fixtures. Shared native fetch lib.rs/executor.rs changes are combined only by integration; closure-worker must prefer new TLS adapter files and request.rs.
- Audit hypotheses are checked before any implementation; unconfirmed risks are not treated as defects.
