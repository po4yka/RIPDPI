---
id: ENG-1794672016726805
title: Repair DPI-30 TLS version and entropy contracts
kind: bug
status: doing
area: engine
priority: high
owner: DPI-30 integrator
parent: null
blocked_by: []
spec_mode: required
openspec_change: dpi30-tls-entropy-contracts
created: 2026-11-14
updated: 2026-11-14
---

## Goal

Correct the six confirmed DPI-30 findings without changing protocol scope.

## Acceptance criteria

- Preserve real TCP payloads in configured and adaptive entropy modes.
- Meet both entropy targets when the budget permits; respect a zero budget.
- Apply TLS minor overrides without SNI and retain multi-record prelude composition.
- Correct record-version and popcount units documentation.
- Pass affected tests, local peer checks, independent review, and ci-gates.

## Parallel ownership

- Integrator: task/OpenSpec/board records, docs/native/proxy-engine.md (F6), combined gates and integration.
- F1 worker: runtime TCP entropy placement, TCP fake construction/emission, associated tests.
- F2/F3 worker: packets entropy.rs, UDP fake_burst shared algorithm call, associated tests.
- F4/F5 worker: TLS prelude and TLS record recognition, associated tests.
- Serialized lanes: integrator owns task/spec/board; no lockfile/schema/locale/baseline/golden changes planned.
- Each writer uses a separate worktree; reviewers are read-only.
