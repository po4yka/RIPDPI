---
title: Decompose RipDpiProxyJsonCodec
type: task
status: backlog
area: service
priority: medium
owner: unassigned
parent: epic-native-hotspot-decomposition
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-14
---

- [ ] #task Decompose RipDpiProxyJsonCodec #repo/RIPDPI #area/service #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `decompose-ripdpiproxyjsoncodec`
- **Verify:** `just test-module core:engine`
- **Scope (only modify these + this file + the ledger):** `core/engine/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

`RipDpiProxyJsonCodec.kt` (708 LOC) mixes schema definition, version
migration, validation, and rewrite concerns.

## Audit citation

- `core/engine/.../RipDpiProxyJsonCodec.kt` — 708 LOC.

## Acceptance criteria

- [ ] Split into: `schema` (field definitions), `migration` (version-to-
    version transforms), `validation` (constraint checks), `rewrite`
    (import/export reshaping).
- [ ] Public API preserved unless simplification is obvious.
- [ ] Existing codec tests still pass; new tests cover migration paths
    independently.
- [ ] `file-loc-baseline.json` updated.

## Links

- [[Epic - Native hotspot decomposition]]
- [[ripdpi-android-audit-2026-04-20]]
