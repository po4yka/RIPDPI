## Context

The navigation rail uses a full-height static `Column`; its last items can overflow short landscape windows. `DnsOptionCard` uses visual selected styling without selected semantics.

## Goals / Non-Goals

- Goal: make rail items scrollable and DNS selection available to accessibility services.
- Non-goal: change destinations, DNS protocols, or visual design.

## Decisions

- Add Compose's `verticalScroll` to the existing rail column; keep its dimensions and item styling.
- Add `selected` to the DNS card semantics beside its existing content description.

## Contracts and ownership

- Affected module: `:app`; no Rust crate, public API, wire or persistence contract.
- `DnsSettingsCards.kt` is serialized with the concurrent DNS change. No locale or migration changes. Board generation is serialized at integration.

## Risks / Trade-offs

- Scrolling changes the rail's gesture behavior only when content exceeds height. Focused short-height tests protect reachability.

## Migration Plan

No migration. Revert the app UI change if needed. Verify focused Compose tests, app lint, and architecture health.
