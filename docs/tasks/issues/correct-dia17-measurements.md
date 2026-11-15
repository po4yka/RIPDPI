---
id: DGN-1794747077897183
title: Correct DIA-17 measurement and evidence handling
kind: bug
status: doing
area: diagnostics
priority: high
owner: Diagnostics integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: correct-dia17-measurements
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Correct the seven confirmed DIA-17 findings. Preserve unknown measurements, respect the network switch, separate TCP timing from local VPN facts, require sample coverage, and stop active I/O on cancellation.

## Acceptance criteria

- Fix F1 through F7 with one code commit per finding and observed regression checks.
- Run affected Android JVM tests, source gates, lint, and an independent review.
- Keep ICMP opt-in reachability and IPv6 outside this repair. Record unverified Android and external compatibility separately.

## Ownership

- integration: DetectionModels, ICMP/RTT outcomes, RttTriangulationChecker, reports/exports, specifications, task records, API snapshots, and final gates.
- ui-worker: presenter and tests (F1), score/exposure and tests (F3), Detection UI execution-state presentation, generated text and all locale sets (F2). No shared core model edits. UI worker owns the locale lane exclusively; integration applies the reviewed patch.
- ping-worker: SystemPingProber code in IcmpSpoofingChecker and process/parser tests (F6, then F7). Do not change the ICMP classifier below the prober.
- timing-worker: TimingAnalysisChecker and its tests (F5). Do not change models or score.
- Each writer uses a separate worktree. Integration alone owns shared files and all final commits. Workers produce one patch per finding and do not commit or push.
