---
title: QA-C: HomeScreenTest cases asserting PCAP toggle gating on rootModeEnabled
type: task
status: doing
area: testing
priority: high
owner: Senior Android Engineer
parent: define-diagnostics-privacy-qa-verification-gate
blocks: []
blocked_by: []
created: 2026-05-04
updated: 2026-05-04
---

- [ ] #task QA-C: HomeScreenTest cases asserting PCAP toggle gating on rootModeEnabled #repo/RIPDPI #area/testing #status/doing ⏫

Owner: Test Automation Engineer (with Senior Android Engineer review for tag/parameter feasibility).
Anchored to: POY-13 CTO PCAP boundary.

## Objective
Prove that the Home full-analysis PCAP toggle is hidden or disabled when `root_mode_enabled=false`, and that turning the setting on surfaces the toggle in the explicit opt-in state.

Observable behavior:
- `app/src/test/kotlin/com/poyka/ripdpi/ui/screens/home/HomeScreenTest.kt` exposes two new tests:
- `pcap toggle hidden when root mode disabled`
- `pcap toggle visible and disabled until opt-in when root mode enabled`
- Tests use `RipDpiTestTags` for stable selectors. If the existing screen does not expose a tag for the PCAP toggle, this issue spawns a follow-up to add one (test code MUST NOT reach into private internals).
- Roborazzi: confirm `RipDpiScreenCatalogScreenshotTest.homeExpandedScreen` baseline correctly reflects rootMode=false default; if a separate baseline is needed for rootMode=true, add `homeExpandedRootedScreen` capture and bless under QA review.

Success metric / test names:
- HomeScreenTest method names listed above.
- Optional new Roborazzi capture in `RipDpiScreenCatalogScreenshotTest`.

Privacy implication:
Yes. Direct verification of the POY-13 boundary that PCAP is opt-in only.

Rollback note:
If rootMode is unavailable on the device class, the toggle must remain hidden (not just disabled). Test must assert `assertDoesNotExist`, not just `assertIsNotEnabled`, in that path.

Non-goals:
- Do not implement the gating logic itself; that belongs to the POY-13 follow-up implementation issue.

## Definition of done
- New tests committed and passing under `./gradlew :app:testDebugUnitTest --tests com.poyka.ripdpi.ui.screens.home.HomeScreenTest`.
- If a new Roborazzi baseline is added, QA Lead reconciles it.
