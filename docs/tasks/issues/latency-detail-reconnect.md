---
id: UIX-1792294575616838
title: Open measured latency details and verify real reconnect
kind: feature
status: doing
area: ui
priority: high
owner: Latency and reconnect integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: latency-detail-reconnect
created: 2026-10-18
updated: 2026-10-18
---

## Goal

Open latency evidence from the measured RTT value and verify real reconnect from Home review.

## Acceptance criteria

- RTT opens real p50/p95, jitter, samples, measurement window and source; no fabricated graph.
- The selected immutable snapshot stays stable across telemetry updates; reopening reads current data.
- Back, explicit dismissal and return focus work without clipped actions in RTL/font2.
- Normal motion uses a short theme transition. Reduced and disabled motion show the final surface directly.
- Real TalkBack can open, read and dismiss details in normal and system-off modes.
- Real service/native/network reconnect verifies cancellation, confirmation and truthful applied state.
- Local tests, theme checks, locale lint, static analysis, independent review and exact-SHA hosted CI pass.
- User-authorized main integration uses rebase, combined gates, ff-only merge and ordinary push.

## Ownership

- Root owns locale resources, generated translation metadata, shared test tags, planning,
  combined compiler gates, integration and publication in RIPDPI-diagnostic-progress-feedback.
- UI worker owns RipDpiDegradationStrip.kt, HomeConnectionMeasurements.kt and new
  latency detail presentation/model helpers in RIPDPI-latency-details-ui.
- Root owns focused host regressions, with independent test design before production changes.
- UI worker also owns new HomeLatencyDetailsAcceptanceTest.kt and
  HomeLatencyDetailsTalkBackSupport.kt in its isolated worktree.
- Device specialist owns new reconnect AndroidTest classes and test-only local
  fixture helpers in RIPDPI-latency-reconnect-device. Reconnect files under
  app/src/androidTest/kotlin/com/poyka/ripdpi/e2e are
  HomeReconnectNetworkAcceptanceTest.kt, HomeReconnectNetworkAcceptanceSupport.kt,
  HomeReconnectTalkBackSupport.kt and HomeReconnectOwnershipJournal.kt.
  It does not modify production services.
  The same writer repairs the mode-specific fixture assumption in these tests
  and the external reconnect driver. VPN retains verified DoT; both Proxy
  lanes use the existing HTTP DoH fixture path and real listener changes.
  This work adds no Proxy custom-CA runtime contract.
- Writers use separate worktrees. Build and emulator lanes are serialized by root.
- The golden specialist owns only HomeMeasurementsScreenshotTest.measured_zero_rtt.png
  and HomeMeasurementsScreenshotTest.stopped_measurements.png in the isolated
  RIPDPI-latency-detail-goldens worktree. The user approved these two fixtures
  on 2026-10-18 after review of the generated before/actual/compare artifacts.
  The contract change is a 48dp RTT action and a 24dp content shift. The same
  update aligns the old warning icon with the Info icon already in main.
- Review is read-only. Quality baselines and all other golden fixtures are unchanged.
- The TalkBack contract writer owns only HomeLatencyDetailsTalkBackSupport.kt
  and HomeReconnectTalkBackSupport.kt in RIPDPI-talkback-action-focus. It also
  owns new external driver copies; the original failed-run drivers are kept.
  The device specialist owns the result-based VPN consent setup change in
  HomeReconnectNetworkAcceptanceSupport.kt in its existing isolated worktree.
  Root combines these test fixes after both device leases end.
- The native transition writer owns the two TalkBack support files in
  RIPDPI-latency-native-transition. The reconnect device specialist owns a new
  external AppOps parser driver copy. Root combines the reviewed source patch
  after the completed temporary matrix and preserves all failed-run inputs.
- The TalkBack exploration writer owns the latency TalkBack helper in
  RIPDPI-latency-talkback-exploration and a new latency driver copy. The device
  specialist owns a new reconnect PCAP driver copy. These fixes preserve the
  previous captures and keep post-dismiss focus checks observational.


- The transition-handshake writer owns HomeLatencyDetailsTalkBackSupport.kt
  in RIPDPI-latency-transition-handshake and a new external latency driver.
  The reconnect acceptance writer owns the four reconnect AndroidTest files
  in RIPDPI-reconnect-runtime-revisions and a new external reconnect driver.
  It adds bounded real scrolling and records only coherent DNS revisions
  from the known owned settings, without accepting unknown runtime owners.
  Root combines these reviewed changes after matrix three cleanup.

- The accessibility-cache writer owns only HomeReconnectTalkBackSupport.kt
  in RIPDPI-reconnect-accessibility-cache. It clears the existing automation
  client cache before a logical tree collection on supported Android versions.
  Root owns a new external latency driver that waits for positive completion
  of prior speech before initial exploration. The strict speech matcher stays
  unchanged. Root integrates these changes after matrix five cleanup.

- The controlled-clock writer owns only HomeLatencyDetailsAcceptanceTest.kt in
  the isolated latency-talkback-fixture-clock worktree. The manual TalkBack
  case uses the existing explicit clock input for its fixed measurement source.
  Other cases retain the live clock. Root owns the new external ReturnHome
  driver copy, which waits for a pending window transition before strict
  destination verification. Both fixes follow matrix six cleanup and review.

## Design sources

Use DESIGN.md, docs/design-system.md and the executable theme. RDS references are
components-metric-pill.html, components-bottom-sheet.html, motion-page-transitions.html
and motion-reduced-motion.html. Adapt the Trackables card-to-detail reference to an
existing modal or in-place detail surface; a cross-window shared-element morph is not required.
The measurement context and return interaction take priority over visual travel.
