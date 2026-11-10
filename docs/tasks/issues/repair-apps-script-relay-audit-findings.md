---
id: RLY-1794300111377171
title: Repair Apps Script relay audit findings
kind: bug
status: review
area: relay
priority: high
owner: Relay audit repair team
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-apps-script-relay
created: 2026-11-10
updated: 2026-11-10
status_detail: F1-F8 implemented and locally checked; inherited staticAnalysis naming failure and physical Google acceptance remain open.
---

## Goal

Repair F1–F8 from the RLY-15 audit and define the peer and trust boundaries.

## Acceptance criteria

Local regression tests, combined source gates, Kotlin compilation and independent review pass. External Google and physical-device acceptance remain explicit evidence gaps.

## Parallel ownership

- Auth worker: native config local credentials and socks_session only (F1).
- HTTP worker: domain_fronter, HTTP relay, tunnel_dispatch and related tests (F2–F6, H2–H5).
- Trust worker: Kotlin/service/engine/UI, locales, native dataDir plumbing and CA export (F7/H1), unused parallel control (F8). Coordinate config.rs with auth worker.
- Integrator: planning/task files, docs, combined validation, reviews and integration.
- Serialized lanes: trust worker owns locale set and EngineContract; no dependency, baseline or golden edits.
