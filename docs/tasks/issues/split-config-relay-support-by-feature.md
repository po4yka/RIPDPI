---
title: Split config relay support by feature
type: task
status: backlog
area: relay
priority: medium
owner: unassigned
parent: epic-post-refactor-architecture-cleanup
blocks: []
blocked_by: []
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Split config relay support by feature #repo/RIPDPI #area/relay #status/backlog 🔼

## Summary

Split `app/src/main/kotlin/com/poyka/ripdpi/activities/ConfigRelaySupport.kt` by relay feature family so config UI support no longer imports and edits every relay kind in one file.

## Context

The re-audit still reports `ConfigRelaySupport.kt` as a high feature-spread Kotlin hotspot. It handles relay presets, credential/profile stores, capability suggestions, Cloudflare tunnel modes, MASQUE auth, chain relay, ShadowTLS, NaiveProxy, Snowflake, VLESS Reality, finalmask, and strategy-chain interactions in one support module.

## Acceptance criteria

- [ ] Extract relay preset application and suggestion logic.
- [ ] Extract credential/profile draft mapping.
- [ ] Extract per-relay-kind draft mapping for MASQUE, Cloudflare tunnel, chain relay, ShadowTLS, NaiveProxy, Snowflake, and local/default paths.
- [ ] Keep the ViewModel-facing API stable or provide a small facade.
- [ ] Reduce feature-family spread for `ConfigRelaySupport.kt` in architecture health.

## Completion outcome

Closing this task means relay config support is owned by relay-kind modules and small shared helpers. Adding or changing MASQUE, Cloudflare tunnel, chain relay, ShadowTLS, NaiveProxy, Snowflake, VLESS Reality, or finalmask behavior should not require editing one shared support file.

## Regression guardrails

- Do not create a new relay `Support` or `Registry` file that contains every backend's mapping and validation logic.
- Do not mix credential/profile-store lookup with per-backend draft construction unless it is behind a narrow shared interface.
- Do not make strategy-chain interactions a dependency of every relay-kind mapper.
- Do not close the task unless feature-family spread drops or is explicitly bounded by per-relay registration.
- Do not close the task without focused unit tests for each extracted relay-kind mapper or support slice.

## Links

- [[Epic - Post-refactor architecture cleanup]]
