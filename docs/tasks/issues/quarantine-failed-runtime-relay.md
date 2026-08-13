---
id: SVC-1786565057976588
title: Quarantine relay profiles after confirmed runtime failure
kind: bug
status: review
area: service
priority: high
risk: standard
owner: codex
parent: null
blocked_by: []
spec_mode: required
openspec_change: quarantine-failed-runtime-relay
created: 2026-08-13
updated: 2026-08-13
related_tasks: []
status_detail: Implementation and required local gates passed; remote CI remains pending after push.
---

## Goal

Prevent a relay whose runtime egress has been actively confirmed as unavailable from being selected repeatedly on the same network while preserving bounded recovery and network isolation.

## Acceptance criteria

- A failed active relay probe records negative evidence for the exact network scope, effective capability proof, relay kind, and profile.
- A matching profile is omitted from later candidate construction until the existing cooldown expires; other networks remain unaffected.
- Successful active confirmation records no failure and preserves existing debounce recovery.
- An observed RED/GREEN GitHub Simple JVM regression, the full affected unit suite, static analysis, architecture health, and strict OpenSpec/task validation pass.
