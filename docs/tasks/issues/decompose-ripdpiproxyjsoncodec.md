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
updated: 2026-04-20
---

- [ ] #task Decompose RipDpiProxyJsonCodec #repo/RIPDPI #area/service #status/backlog 🔼

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
