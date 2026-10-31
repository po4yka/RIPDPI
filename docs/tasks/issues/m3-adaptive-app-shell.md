---
id: UIX-1793256870879296
title: Move the app shell to adaptive Material 3 navigation
kind: feature
status: done
area: ui
priority: high
owner: UI overhaul
parent: EPC-1793256695505524
blocked_by: []
spec_mode: required
openspec_change: m3-adaptive-app-shell
created: 2026-10-29
updated: 2026-10-31
closed_at: "2026-10-31T12:54:54Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: CI run 41188616435 passed 50 jobs at 71d272bb8; emulator check passed top bar, predictive back, unsaved-changes guard and expanded rail; owner-approved shell goldens re-verified in both variants.
---

## Goal

Give the app one adaptive Material 3 shell: navigation that changes form with the window size class, Material 3 top app bars, and predictive back animations.

## Acceptance criteria

- NavigationSuiteScaffold replaces BottomNavBar and RipDpiNavRail and shows a bar on compact windows, a rail on medium and expanded windows, and a rail or drawer on large and extra-large windows.
- One window size class source, including large and extra-large, drives the shell and RipDpiLayout.
- Top-level and detail screens use Material 3 top app bars with scroll behavior; large titles still wrap at 200% font.
- Back navigation shows the system predictive back animation and in-app predictive transitions.
- Destination order, selection, labels, and automation IDs of top-level destinations stay the same.

## Ownership

Single writer on `main` unless the execution notes assign separate worktrees. Serialized files follow the epic EPC-1793256695505524.
