---
title: Fix Pixel 7 UI audit P2 findings
type: task
status: doing
area: ui
priority: medium
owner: Codex
parent: null
blocks: []
blocked_by: []
created: 2026-07-18
updated: 2026-07-18
---

## Goal

Close every confirmed P2 finding from the Pixel 7 Full and Simple UI/UX audit, and resolve the remaining performance finding with profileable evidence.

## Scope

- Clarify the Full Home connection hierarchy so the primary connection state and action no longer compete with redundant technical status and mode controls.
- Preserve complete connection-stage meaning and a reachable primary action at large font scales and compact heights.
- Re-measure Full tab transitions in a profileable build; fix only a reproduced production-relevant hotspot.
- Make Asset Provider import actions readable on compact screens, single-flight with visible progress, and backed by localized typed failures.
- Protect unsaved Mode Editor and Strategy Config drafts from accidental Up or system Back navigation.
- Make History `Clear all` include the search query and give Logs rows a discoverable, accessible copy action.
- Surface completed Simple diagnostic results and their share action instead of ending at a generic terminal label.
- Make Simple diagnostic action availability and its supporting explanation follow the authoritative UI state.
- Center and width-limit Simple content on expanded and TV layouts.

## Ship definition

- [ ] Full Home has a tested, visually reviewed primary hierarchy in compact, large-font, and representative connection states.
- [ ] Simple diagnostics expose actionable completed results, authoritative disabled states, and adaptive expanded-width layout.
- [ ] The profileable performance measurement either passes its agreed threshold or identifies and verifies a narrow root-cause fix.
- [ ] Targeted Compose, Roborazzi, lint, detekt, locale, architecture, and both GitHub variant build gates pass.
- [ ] Both variants pass the affected journeys on the connected Pixel 7, including 150% font and an expanded-surface check where supported.
- [ ] Independent review finds no unresolved P1/P2 issue in the combined diff.
- [ ] Atomic commits are rebased onto current `origin/main`, fast-forwarded to `main`, pushed, and verified by exact remote SHA.

## Work log

- 2026-07-18: Recovered the authoritative audit artifacts and revalidated current `origin/main`. Two Full product P2 findings remain unchanged; the debug jank finding remains a profileable verification item rather than a confirmed source defect.
- 2026-07-18: Independent Simple re-audit found three additional reproducible P2 gaps: completed-result dead end, ignored action availability, and unbounded expanded-width layout.
- 2026-07-18: Independent Full settings/diagnostics re-audit added seven reproducible P2 gaps: truncated and concurrent asset imports, raw asset errors, two unsaved-draft loss paths, incomplete History reset, and inaccessible Logs copy interaction. The final inventory is twelve product P2 findings plus one profileable performance verification item; P1 findings remain at zero.
