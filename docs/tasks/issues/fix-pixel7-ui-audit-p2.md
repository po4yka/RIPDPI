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
updated: 2026-07-22
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
- 2026-07-22: Final serial validation passed both canonical Full/Simple Roborazzi matrices, `staticAnalysis`, both arm64 GitHub debug assemblies with Rust/JNI, and the Pixel 7 Android Keystore regression. Independent review found and closed a false Simple-golden provenance oracle and confirmed no remaining source-level P1/P2 findings.
- 2026-07-22: Final physical UI and profileable evidence remains blocked by the secure Pixel 7 lockscreen. The exact Simple integration test launches `MainActivity`, but the lockscreen immediately moves it from `RESUMED` to `PAUSED`/`STOPPED`; the rerun is not accepted as green. An earlier five-run profileable artifact passed 4.13% median jank and 14 ms median p90, but its APK SHA differs from the current build and therefore is not final evidence.

## Completion matrix

Evidence legend: `R` = canonical Full/Simple Roborazzi passed; `K` = Pixel 7 Keystore test passed; `D` = fresh physical UI evidence pending; `P` = fresh profileable evidence pending.

### Original product P2 findings

| Finding | Root cause | Primary files | Regression evidence | Commit(s) | Status |
|---|---|---|---|---|---|
| Full Home hierarchy | Connection state/action competed with redundant mode controls | `HomeScreen.kt`, `HomeModeCard.kt`, `RipDpiConnectionActuator.kt` | Home screen/route/direct-action tests and Home goldens | `53758602f`, `438c31efd` | R; D pending |
| Large-font connection stages | Stage labels/action overlapped or disappeared on compact height | `RipDpiConnectionActuator.kt`, `HomeScreen.kt` | Actuator/Home chrome tests and Pixel 7 Home goldens | `3fd9226fb`, `7008c2828`, `09e33c200` | R; D pending |
| Asset import readability | Horizontal actions truncated at compact/large font sizes | `AssetProviderScreen.kt` | `AssetProviderScreenScreenshotTest` | `d94bc6dea` | R |
| Concurrent asset actions | Operations lacked one coherent single-flight busy state | `AssetProviderViewModel.kt`, `AssetProviderScreen.kt` | ViewModel/screen tests and busy-state goldens | `39d32af35`, `30f4564dd`, `9fc3968ee` | R |
| Raw asset failures | Repository causes collapsed into untyped UI errors | `AssetProviderViewModel.kt`, `GeoAssetRepository.kt` | ViewModel/repository failure tests | `2ba6c4e6b`, `d45b96b34`, `735508cec`, `1af9d1958`, `427acfb14` | Unit + R |
| Mode Editor Up/Back loss | Dirty draft navigation had no guarded exit contract | `ConfigViewModel.kt`, `ModeEditorRoute.kt`, `UnsavedChangesDialog.kt` | Editor session/screen/dialog tests | `ae14f8f4a` | Unit; D pending |
| Strategy Editor Up/Back loss | Dirty strategy draft navigation had no guarded exit contract | `StrategyConfigEditorSession.kt`, `StrategyConfigRoute.kt`, `StrategyConfigScreen.kt` | Strategy session/screen tests | `9e9b2a36e`, `7047a1746` | Unit + R |
| Incomplete History reset | Clear-all retained the active search query | `HistoryConnectionActions.kt`, `HistoryFilters.kt` | History actions/filter tests | `26fdb779c` | Unit |
| Undiscoverable Logs copy | Copy affordance was hidden and poorly described to accessibility | `LogsScreen.kt`, `RipDpiIcons.kt` | Logs semantics tests and copy-action golden | `0e3349fbc`, `f98adca26`, `318b1bd87`, `c29cd05f3` | R |
| Simple diagnostic dead end | Completion reduced results to a generic terminal label | `SimpleHomeScreen.kt` | Simple Home unit and Roborazzi tests | `af2f8061e` | Simple Home Roborazzi 9/9 |
| Incorrect Simple action state | UI ignored authoritative availability and allowed connection/scan conflicts | `SimpleHomeScreen.kt` | Simple Home tests and exact navigation instrumented test | `af2f8061e`, `17caa474d` | Unit + R; exact device rerun D pending |
| Unbounded Simple expanded layout | Content expanded edge-to-edge on TV/large surfaces | `SimpleHomeScreen.kt` | Expanded, dark large-font, and RTL Simple goldens | `4cb64f0b0`, `1739de8a6`, `97eeb3596` | Simple Home Roborazzi 9/9; D pending |

### Review-discovered P1/P2 follow-ups

| Finding | Root cause | Primary files / regression | Commit(s) | Status |
|---|---|---|---|---|
| Loading ignored reduced/static motion | Raw spinner animation did not honor the design-system motion contract | `RipDpiButton.kt`, `RipDpiSpinner.kt`; static-motion test | `fc723301b` | R |
| Strategy process-death loss | Draft survived configuration change but had no durable bounded recovery store | Strategy draft store/ViewModel; store, route, and ViewModel tests | `7e26def4f`, `31940a774`, `0f04d885f`, `63a83b31a` | Unit + R |
| Mode false discard/data loss | Save, hydration, and session transitions could clear a newer draft | Config ViewModel/save/recovery support; concurrency and failure tests | `cd63312de`, `6183d7141`, `5c57af740`, `0ec096159`, `4bc84a1b8`, `5774cd904` | Unit |
| Unsafe Mode process recovery | Sensitive draft recovery was not bounded, encrypted, and durably invalidated | Config draft store/recovery support; store/ViewModel/sensitive-state tests | `1453e3881`, `89502d92f` | K: Pixel 7 1/1 |
| Swallowed projection cancellation | Broad exception handling converted coroutine cancellation into empty relay state | `ConfigUiStateFactory.kt`; cancellation/storage-error tests | `3400563b4` | Focused regression |
| Strategy persistence races | Retry/save cancellation could retain saving state or lose newer edits | Strategy session/ViewModel/route; retry and concurrency tests | `299c415a6`, `55a8f7a5c`, `47b678bbb`, `5335abedb` | Unit + R |
| Asset persistence/exit races | Stale values could win and exit could precede durable completion | `AssetProviderViewModel.kt`; persistence/recreation/exit tests | `3d90acc02`, `e6572a54d`, `ce814a17f`, `95b4d0476` | Unit + R |
| Navigation/diagnostics accessibility | Duplicate labels, missing focus/selected/group roles, and non-mirrored RTL indicator | Bottom navigation and diagnostics section; semantics/multilingual goldens | `2dadca0c3`, `650e7c326`, `3b1f39d13`, `b6d7ad377`, `a62556fee` | R; TalkBack/D-pad D pending |
| Maximum-font regressions | About, onboarding, diagnostics/settings, Strategy actions, and Connection Health lacked adaptive reflow | Affected screens; unit/screenshot tests | `5ba5167ba`, `249549cbe`, `0b2497c00`, `4b9082044`, `75983213b`, `b59d0eb19`, `7f6c815c7` | R; physical 150/200% D pending |
| Repeated Simple announcements | Elapsed-time updates retriggered the running live-region message | `SimpleHomeScreen.kt`; announcement-state test | `8cb50f1a1` | Unit; TalkBack D pending |
| False Simple golden provenance | Full `HomeScreen` rendered under `_simple_` filenames | Screenshot capture support/contract; false PNGs removed and provenance variants verified | `5f8ec8080` (after corrective history `bde9b75c7`, `7a6898c70`) | Full 5/5; Simple 5 total/4 expected skips; R |
| Keystore regression did not execute | Expression-body returned `Boolean`, invalidating the JUnit4 `void` method contract | `ConfigEditorDraftKeystoreInstrumentedTest.kt` | `abf295720` | K: Pixel 7 1/1 |

### Remaining completion evidence

- `D`: fresh Pixel 7 Full then Simple journeys at 100%, 150%, and 200% font scale, expanded/landscape, RTL, TalkBack, and D-pad focus.
- `D`: exact Simple diagnostic start/cancel/share integration rerun after manual unlock.
- `P`: five fresh profileable Full tab-transition runs from the current APK, with median jank at most 15% and median p90 at most 90 ms.
- Final `fetch`/rebase onto current `origin/main`, collision-prone gates, fast-forward integration, push, and exact local/remote SHA equality.
