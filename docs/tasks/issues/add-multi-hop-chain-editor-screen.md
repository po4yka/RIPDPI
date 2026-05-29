---
title: Add multi-hop chain editor screen
type: task
status: backlog
area: ui
priority: medium
owner: unassigned
parent: epic-multi-hop-proxy-chains
blocks: []
blocked_by: []
created: 2026-05-29
updated: 2026-05-29
---

- [ ] #task Add multi-hop chain editor screen #repo/RIPDPI #area/ui #status/backlog 🔼

## Summary

Add a Compose screen to build and edit a chain relay as an ordered list of hops (add / remove / drag-reorder), surfacing the per-hop and cumulative trust/latency caveat.

## Context

With N-hop chains supported in the model ([[Generalize chain relay to N hops model and migration]]) and runtime ([[Add N-hop native chain composition]]), the user needs a surface to compose them. Today the chain UI assumes exactly entry + exit. The new screen must consume RDS tokens and ship all 7 locales.

## Acceptance criteria

- [ ] Screen lets the user add up to N hops, remove hops, and drag-reorder; enforces the min-2 / max-N bounds with inline feedback.
- [ ] Each hop picks an existing relay profile/group; first match is the entry, last is the exit.
- [ ] Per-hop and cumulative latency / anonymity caveat is shown (mirrors the Tor caveat treatment).
- [ ] Uses only `RipDpiTheme` / `RipDpiMotion` / `RipDpiSurface` / `RipDpiState` tokens — no `Color(0x…)`, `.dp`, or literal `tween(…)` outside `ui/theme/` (per `.claude/rules/rds-spec.md`).
- [ ] New strings land in all 7 locales (`values/`, `values-ru/`, `-es/`, `-de/`, `-fr/`, `-fa/`, `-zh-rCN/`); `lint.xml` `MissingTranslation` stays clean.
- [ ] A compose-preview render is added for the screen; linked RDS spec card referenced (or a one-line RDS-deviation justification if the card does not yet exist).

## Source references

**Reference (xivpn):** `ProxyChainActivity` / `ProxyChainAdapter` (ordered chain editing UX) — interaction pattern only; reimplement in Compose.

**Adapt:** the existing chain UI's profile-picker affordance.

**Invent:** drag-reorder for N hops, per-hop caveat surfacing under the RDS token contract.

## Links

- [[Epic - Multi-hop proxy chains]]
