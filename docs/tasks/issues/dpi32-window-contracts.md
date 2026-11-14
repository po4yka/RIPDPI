---
id: PRX-1794677617943037
title: Repair TCP window configuration contracts and evidence
kind: bug
status: doing
area: proxy
priority: high
owner: Window repair integrator
parent: null
blocked_by: []
spec_mode: required
openspec_change: dpi32-window-contracts
created: 2026-11-14
updated: 2026-11-14
---

## Goal

Repair DPI-32 with bounded input, honest outcomes, explicit admission, and independent window checks.

## Acceptance criteria

- All six REQ-WINDOW requirements have local evidence.
- Separate repair commits pass independent review and required gates before main integration and push.
- Capture, Android and external evidence stay separate from host proof.

## Parallel ownership

Integrator owns portfolio/OpenSpec, admission, API snapshots, combined gates, and integration. window_values owns config/model/CLI, numeric conversion, tcp_connect model, proto comments, Kotlin scale writer/tests, and numeric setters. window_outcomes owns platform outcomes/events, runtime completion, getters/facades and related tests. window_wire owns tests/wire_e2e.rs and tests/support/wire*. Each writer uses an isolated worktree.

Serialized lane: privileged-ops/linux/socket_options.rs belongs to window_values until its numeric commit, then window_outcomes may change getters. Proto comments belong only to window_values. API snapshots belong only to the integrator. No dependency, locale, golden or baseline changes.
