---
id: UIX-1793256952721314
title: Apply Material 3 canonical layouts to screens
kind: feature
status: backlog
area: ui
priority: medium
owner: UI overhaul
parent: EPC-1793256695505524
blocked_by: [UIX-1793256927624290]
spec_mode: required
openspec_change: m3-screen-layouts
created: 2026-10-29
updated: 2026-10-29
---

## Goal

Rearrange the main screen families to Material 3 canonical layouts and action placement so each window size class gets a layout made for it.

## Acceptance criteria

- Diagnostics, History, Logs, and Settings use a list-detail layout that shows both panes on expanded and wider windows and one pane on compact windows.
- Config editors use a supporting-pane layout on expanded and wider windows.
- Home uses a feed or supporting-pane arrangement on expanded and wider windows and keeps the connection actuator as the primary control.
- Primary screen actions use Material 3 placement: FAB or extended FAB, top app bar actions, or a floating toolbar.
- Logs and History offer a Material 3 search bar.
- The remaining screen families adopt the facade, app bars, and width rules without dedicated layout redesign.
- Back navigation between panes works with predictive back.

## Ownership

Single writer on `main` unless the execution notes assign separate worktrees. Serialized files follow the epic EPC-1793256695505524.
