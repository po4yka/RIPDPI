---
id: DGN-1791525730340153
title: Explain local device and SIM constraints in diagnostics
kind: feature
status: review
area: diagnostics
priority: high
owner: sim-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: local-sim-constraints
created: 2026-10-09
updated: 2026-10-09
status_detail: Implementation and local gates passed; hosted CI, physical-device and APK artifact acceptance remain pending.
---

## Goal

Explain device and default-data SIM states that can affect connectivity, without inferring provider policy.

## Acceptance criteria

- Collect identifier-free local and SIM evidence across Wi-Fi, VPN and no-network states.
- Preserve unavailable, permission-denied and unsupported values.
- Show localized causes and evidence in current/history/live context and safe exports.
- Pass collector, assessment, compatibility, privacy, UI, static-analysis and locale checks.

## Ownership

- Integration writer owns models, pure assessment, export integration, documentation and task records.
- Collector writer owns new AndroidLocal* collector files/tests and DiagnosticsContextProvider.kt in an isolated worktree.
- UI writer owns local/SIM app mapping, app tests and all ten locale sets in an isolated worktree.
- Integration writer serializes Gradle and Git integration. No concurrent writes to models, golden fixtures or schemas.
