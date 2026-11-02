## Context

The Material 3 overhaul gave every card the same outlined look on the page color. The audit on 2026-11-01 found that this flattens the hierarchy. About 20 call sites use the Elevated or Status card role for ordinary content. This conflicts with `DESIGN.md`, which says default cards stay flat.

## Goals / Non-Goals

- Goal: one card rule, documented once and applied at every call site.
- Goal: primary and secondary content differ in weight without extra decoration.
- Non-goal: new card components or new color tokens.
- Non-goal: changes to card content, spacing, or behavior.

## Decisions

- Grouping uses `surfaceContainer` tonal steps. Nesting goes one step up per level.
- An outline marks only interactive or selected cards.
- A shadow marks only hero or selected cards.
- The Elevated and Status roles stay available for hero and status cards only. Each call site moves to the role that matches its use under the rule.
- The `RipDpiRemediationCard` default becomes tonal and flat.
- Re-grep the Elevated and Status roles before editing. The audit line numbers can drift.

## Risks / Trade-offs

- A tonal fill can lose contrast against the page color in one theme. → Measure contrast from a device capture in light and dark, as the Roborazzi threshold can miss thin edges.
- Golden churn is large. → One writer re-records goldens on the combined tree with owner approval, per `golden-bless-discipline.md`.
- A selected card loses its outline if the rule is applied blindly. → Selected state keeps an outline or shadow by rule.

## Migration Plan

Implement in an isolated worktree. Change the documents first, then the call sites, then re-record goldens and capture the device views. Archive only through `taskctl` after review.
