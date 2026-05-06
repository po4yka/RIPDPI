---
title: Decentralize advanced settings taxonomy
type: task
status: done
area: ui
priority: medium
owner: unassigned
parent: epic-finish-srp-residual-architecture-debt
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [x] #task Decentralize advanced settings taxonomy #repo/RIPDPI #area/ui #status/done 🔼

## Summary

The advanced settings screen was split into section files, but setting
identifiers and the action contract remain centralized across diagnostics,
command-line settings, desync, QUIC, WARP, autolearn, adaptive fallback,
entropy, routing protection, and host/strategy packs.

## Audit citation

- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/settings/AdvancedSettingsTaxonomy.kt` lines 11 onward.

## Scope

- In scope: setting identifiers, action contracts, section-owned taxonomy, and
  route wiring.
- Out of scope: visual redesign or changing user-visible settings behavior.

## Acceptance criteria

- [x] Feature sections own their setting identifiers and action payloads.
- [x] Adding a setting for one feature does not require editing a broad shared
    taxonomy surface.
- [x] Route-level code composes feature-owned taxonomy providers.
- [x] Advanced settings unit/screenshot coverage remains green.

## Links

- [[Epic - Finish SRP residual architecture debt]]
