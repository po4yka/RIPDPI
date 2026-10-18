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
- Device specialist owns new latency/reconnect AndroidTest classes and test-only local
  fixture helpers in RIPDPI-latency-reconnect-device. It does not modify production services.
- Writers use separate worktrees. Build and emulator lanes are serialized by root.
- Review is read-only. Golden fixtures and quality baselines are not approved for changes.

## Design sources

Use DESIGN.md, docs/design-system.md and the executable theme. RDS references are
components-metric-pill.html, components-bottom-sheet.html, motion-page-transitions.html
and motion-reduced-motion.html. Adapt the Trackables card-to-detail reference to an
existing modal or in-place detail surface; a cross-window shared-element morph is not required.
The measurement context and return interaction take priority over visual travel.
