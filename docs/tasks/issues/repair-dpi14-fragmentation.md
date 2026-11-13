---
id: RST-1794557321446019
title: Repair DPI-14 TCP fragmentation contracts
kind: bug
status: doing
area: rust-native
priority: high
owner: primary
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-dpi14-fragmentation
created: 2026-11-13
updated: 2026-11-13
---

## Goal

Resolve DPI-14 F1-F9 while preserving application bytes and IP packet contracts.

## Acceptance criteria

Each finding has a separate tested implementation commit. Narrow Rust/Kotlin tests, packet reassembly, independent review and combined source gates pass. Unexecuted privileged wire and device acceptance are reported explicitly.

## Ownership

Primary owns planner/progress/adapter integration and task artifacts. Validation owns Kotlin/Rust chain validation. Wire owns privileged fragmentation/MTU, runtime fragment IDs and IP length guards. Helper owns the serialized IPC schema/version/Kotlin callers, helper handlers and client transport lane. All writers use separate worktrees; no writer reverts another writer. Dependency, locale, baseline and golden files are not assigned for change.
