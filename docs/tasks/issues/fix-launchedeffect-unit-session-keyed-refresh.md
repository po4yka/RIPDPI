---
id: UIX-1786264762917972
title: Key session-scoped LaunchedEffect refreshes on the session id, not Unit
kind: feature
status: backlog
area: ui
priority: medium
owner: unassigned
parent: EPC-1786264762917503
blocked_by: []
spec_mode: required
openspec_change: uix-1786264762917972-fix-launchedeffect-unit-session-keyed-refresh
created: 2026-06-10
updated: 2026-06-10
source_wiki_pages: []
linked_task: null
---

## Motivation

The 2026-06-10 Compose audit found three `LaunchedEffect(Unit)` sites that drive ViewModel data refresh keyed on `Unit`:

- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/ReplayHistoryRoute.kt:21`
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/ReplayFailureRoute.kt:25`
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/scanner/QrScannerScreen.kt:77`

`LaunchedEffect(Unit)` re-runs only on composition entry. If the same composable is reused for a different session/nav-argument without leaving composition (e.g. `ReplayHistoryRoute` calling `viewModel.refresh()`), the data does not refresh for the new key — a stale-data bug. The correct key is the session id / nav argument, not `Unit`.

(The `LaunchedEffect(Unit)` sites that are genuinely one-shot — focus requests in `RipDpiCommandPalette.kt:71`, etc. — are correct and out of scope.)

## Proposed change

1. For each of the three sites, change the `LaunchedEffect` key from `Unit` to the session id / nav argument that the refresh depends on, so the effect re-runs when the key changes.
2. Verify the surrounding nav graph: confirm whether the composable is actually reused across keys (if every navigation pushes a fresh entry, the bug is latent — still fix for correctness and document).

## Acceptance criteria

- [ ] PR confirms current state at the three cited sites.
- [ ] Each refresh `LaunchedEffect` keys on the data-determining argument, not `Unit`.
- [ ] Test (Compose/Robolectric or unit on the VM): changing the session key triggers a refresh.
- [ ] `./gradlew :app:testDebugUnitTest --locked` green; goldens unchanged.

## Risks / open questions

- If the nav graph always creates fresh composition per key, this is a latent (not active) bug — still worth fixing, but note it in the work log so the test reflects reality.

## References

- Audit memory `project_native_audit_findings.md` (2026-06-10, item 12 / C-1).
- `compose` skill (state, recomposition, effect keys).
