---
title: Fix Pixel 7 UI audit P2 findings
type: task
status: doing
area: ui
priority: high
owner: Codex
parent: null
blocks: []
blocked_by: []
created: 2026-07-18
updated: 2026-07-19
---

## Goal

Close every confirmed P2 finding from the Pixel 7 Full and Simple UI/UX audit, and resolve the remaining performance finding with profileable evidence.

## Scope

- Clarify the Full Home connection hierarchy so the primary connection state and action no longer compete with redundant technical status and mode controls.
- Preserve complete connection-stage meaning and a reachable primary action at large font scales and compact heights.
- Re-measure Full tab transitions in a profileable build; fix only a reproduced production-relevant hotspot.
- Make Asset Provider import actions readable on compact screens, single-flight with visible progress, and backed by localized typed failures.
- Make loading buttons respect reduced/static motion so busy states remain accessible and deterministically renderable.
- Protect unsaved Mode Editor and Strategy Config drafts from accidental Up or system Back navigation.
- Preserve a bounded Strategy Config draft across system process death without placing draft payloads in saved-state bundles.
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
- 2026-07-18: Parallel completion lanes assigned: `home_p2_worker` exclusively owns Full Home/connection-actuator source, tests, and Home goldens in an isolated worktree; the coordinator exclusively owns Pixel 7 profileable measurement and final integration. Home goldens and locale resources remain serialized to the Home lane until its commit is reviewed.
- 2026-07-18: Combined review follow-ups assigned to isolated serialized lanes: `home_p2_worker` retains exclusive ownership of Home/actuator source, tests, and Home/locale goldens; `mode_p2_followup` owns Mode Editor hydration/save failure and race handling; `asset_p2_followup` owns Asset Provider configuration locking during active operations. The coordinator owns Strategy Config recreation safety, the visible Logs copy affordance, final combined review, Pixel 7 verification, and integration.
- 2026-07-18: Independent recreation review confirmed Strategy drafts survive configuration changes but not system process death. `strategy_process_death_worker` exclusively owns the private atomic draft store, opaque saved-state identifier, restoration/cleanup logic, and focused regression tests in an isolated worktree; the coordinator retains Strategy visual fixtures and integration.
- 2026-07-18: Focused Asset busy-state recording exposed a shared loading-button motion defect: the raw Material spinner ignored the design-system static-motion contract and prevented capture teardown. `asset_p2_followup` additionally owns the narrow `RipDpiButton` loading-glyph fix and its focused regression/golden evidence; no other shared component is in scope.
- 2026-07-19: Final combined review found one false-discard P1 and follow-up P2 gaps. Isolated ownership is serialized by boundary: `final_mode_state_worker` owns Mode Editor save/exit, preset-session, and MASQUE callback races; `final_strategy_state_worker` owns Strategy exit/save/draft-store failure safety; `final_asset_storage_worker` owns typed install/storage failures; `final_home_visual_worker` exclusively owns Home/actuator adaptive layouts, Home goldens, variant isolation, automation selectors, and related UI documentation. The coordinator owns Logs semantics, About large-font behavior, final Pixel 7/profileable verification, recombination, and integration. Locale sets and Home goldens remain exclusive to the Home lane.
- 2026-07-21: Post-fix independent review found no P1 and six remaining P2 defects plus two visual P2 regressions. Isolated serialized ownership: `asset_provider_p2` owns only Asset Provider ViewModel persistence/recreation source and tests; `config_draft_recovery` owns only Mode Editor bounded recovery source and tests; `bottom_nav_semantics_followup` owns only BottomNav semantics source and tests. The coordinator exclusively owns Strategy/Simple feedback fixes, dropdown and onboarding adaptive layout, all locale resources, all Roborazzi goldens, final device/performance evidence, task closure, recombination, and integration.
