---
id: UIX-1792244042561922
title: Clarify connection feedback and reconnect changes
kind: feature
status: doing
area: ui
priority: high
owner: UI transition integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: ui-transition-clarity
created: 2026-10-17
updated: 2026-10-17
---

## Goal

Make History motion follow reading direction, show honest connection state changes,
and explain safe saved settings before a confirmed reconnect.

## Acceptance criteria

- History forward and reverse transitions mirror in RTL and preserve section state.
- Connection feedback transitions only on status changes. Cancel stays available; static motion snaps and reduced motion has no travel.
- Reconnect confirmation compares whitelisted applied and saved facets, labels unknown values, and never promises an effective route from saved intent.
- A changed runtime or settings snapshot invalidates an open confirmation. Cancellation and stale confirmation do not reconnect.
- No secrets, endpoint hosts, raw CLI, profile names, or configuration hashes enter display, semantics, or logs.
- Meaningful RED/GREEN tests, theme checks, locale lint, static analysis, independent review, and real APK emulator checks pass.
- Rebase and combined checks precede authorized ff-only integration, push, and exact-SHA hosted CI inspection.

## Ownership

- Root owns all locale resource sets, translation manifest, shared test tags, MainUiStateOwner and MainUiInputs wiring, MainViewModel flow tests, focused device acceptance tests, planning, integration, compiler-backed checks, and publication in RIPDPI-ui-transition-clarity.
- History worker owns HistoryScreen.kt, a local transition helper, and focused History tests in RIPDPI-history-rtl-transitions.
- Connection worker owns RipDpiConnectionActuator.kt, a local feedback helper, and focused actuator tests in RIPDPI-connection-state-feedback.
- Reconnect worker owns RuntimeConfigurationPresentation.kt, an adjacent safe comparison projection, the applied-configuration portion of MainUiModels.kt, HomeAppliedConfigurationPanel.kt, and focused tests in RIPDPI-reconnect-settings-preview.
- Each writer uses its own worktree. Root observes test-only RED before production patches. Implementation workers do not run compilers, use devices, or edit serialized files.
- The device specialist owns only UiTransitionClarityInstrumentedTest.kt under app/src/androidTest in RIPDPI-ui-transition-device. Root applies its patch. After the source commit and root local gates, the device specialist owns one serialized compiler lane for read-only runtime-panel golden checks, real APK builds, leased execution, and evidence. Root does not run compilers during that lane.
- Review is read-only. Device work uses an exclusive command-scoped pool lease. No golden blessing, quality baseline, dependency, Rust, service, JNI, protobuf, storage, or network behavior change is planned.

## Design references

Use motion-page-transitions.html, motion-reduced-motion.html,
motion-connection-states.html, and vpn-confirm-disconnect.html under the RDS
preview directory. The reconnect comparison has no exact RDS sheet; use existing
dialog/card roles and stacked values at large font. Preview sources stay read-only.
