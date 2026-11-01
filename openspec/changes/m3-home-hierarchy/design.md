## Context

The audit on 2026-11-01 captured Home on a phone and a tablet. The system lockdown warning is a full-size amber card. It pushes connection health down and leaves empty space below. On the tablet the layout is a single 800px column with a floating side column that does not line up.

## Goals / Non-Goals

- Goal: status first. State, duration, and strategy sit in one hero.
- Goal: notices stay visible but compact.
- Goal: a layout with no dead area on phone and tablet.
- Non-goal: new connection features or new notice types.
- Non-goal: changes to the connection flow or the mode selection logic.

## Decisions

- The status hero is the first element in every window size class.
- Notices collapse into a compact banner or chip. A tap on it expands the full text, so no information is lost.
- The primary button has a maximum width. It stays centered in wide layouts.
- The tablet layout uses a two-column arrangement where both columns share one top edge.
- Any new string key lands in every locale in the same commit and in `config/i18n/translatable-keys.txt`.

## Risks / Trade-offs

- A compact notice can hide a warning that matters. → The chip keeps the severity color and an accessible label, and the full text opens on tap.
- Golden churn on Home. → One writer re-records goldens on the combined tree with owner approval.
- The tablet layout can regress other window sizes. → Verify compact, medium, and expanded goldens.

## Migration Plan

Implement in an isolated worktree. Build the hero first, then the notice banner, then the tablet layout. Re-record goldens, capture the device views, and archive only through `taskctl` after review.
