---
title: Surface typed cache-degradation reasons
type: task
status: done
area: engine
priority: high
owner: Senior Android Engineer
parent: epic-control-plane-hardening
blocks: [decouple-jni-handle-lifetime-and-telemetry-locking]
blocked_by: []
created: 2026-04-20
updated: 2026-05-16
---

- [x] #task Surface typed cache-degradation reasons #repo/RIPDPI #area/engine #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `surface-typed-cache-degradation-reasons`
- **Verify:** `just test-module core:engine`
- **Scope (only modify these + this file + the ledger):** `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Cache parse failures currently degrade silently to empty/default state via
`runCatching{...}.getOrDefault(...)` / `getOrNull()`. Operators can't tell
"empty by design" from "cache damaged."

## Audit citation

- `app/.../hosts/HostPackCatalogRepository.kt:139-154`
- `app/.../strategy/StrategyPackRepository.kt:145-165`

## Acceptance criteria

- [x] Add a metadata envelope around each cached snapshot: `schema_version`,
    `stored_at`, `source` (bundled / fetched).
- [x] Parse failures produce a typed `CacheDegradation` value
    (`Missing`, `SchemaMismatch`, `SignatureInvalid`, `Corrupt`, …) instead
    of null.
- [x] Degradation reason is emitted as telemetry and visible in diagnostics.
- [x] Callers that intentionally allow fallback opt in explicitly; they no
    longer mask corruption by accident.

## Links

- [[Epic - Control-plane hardening]]
- [[ripdpi-android-audit-2026-04-20]]


## review
