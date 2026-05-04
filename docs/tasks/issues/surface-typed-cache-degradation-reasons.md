---
title: Surface typed cache-degradation reasons
type: task
status: doing
area: engine
priority: high
owner: Senior Android Engineer
parent: epic-control-plane-hardening
blocks: [decouple-jni-handle-lifetime-and-telemetry-locking]
blocked_by: []
created: 2026-04-20
updated: 2026-04-23
---

- [ ] #task Surface typed cache-degradation reasons #repo/RIPDPI #area/engine #status/doing ⏫

## Summary

Cache parse failures currently degrade silently to empty/default state via
`runCatching{...}.getOrDefault(...)` / `getOrNull()`. Operators can't tell
"empty by design" from "cache damaged."

## Audit citation

- `app/.../hosts/HostPackCatalogRepository.kt:139-154`
- `app/.../strategy/StrategyPackRepository.kt:145-165`

## Acceptance criteria

- [ ] Add a metadata envelope around each cached snapshot: `schema_version`,
    `stored_at`, `source` (bundled / fetched).
- [ ] Parse failures produce a typed `CacheDegradation` value
    (`Missing`, `SchemaMismatch`, `SignatureInvalid`, `Corrupt`, …) instead
    of null.
- [ ] Degradation reason is emitted as telemetry and visible in diagnostics.
- [ ] Callers that intentionally allow fallback opt in explicitly; they no
    longer mask corruption by accident.

## Links

- [[Epic - Control-plane hardening]]
- [[ripdpi-android-audit-2026-04-20]]


## review
