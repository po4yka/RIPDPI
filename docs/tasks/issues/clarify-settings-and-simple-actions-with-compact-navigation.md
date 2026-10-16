---
id: UIX-1792145277214284
title: Clarify settings and Simple actions with compact navigation
kind: feature
status: doing
area: ui
priority: high
owner: UI stage three team
parent: null
blocked_by: []
spec_mode: required
openspec_change: ui-stage-three
created: 2026-10-16
updated: 2026-10-16
---

## Goal

Implement stage three of the design audit: clear settings groups, truthful
Simple diagnostic consequences and backup access, neutral measurement status,
and compact navigation at large fonts. Repair the translation-export CI gate.

## Acceptance criteria

- Settings use Connection, Interface, Privacy and Support groups. Current values
  are truthful; navigation-only rows do not repeat Manage. All repairs remain.
- Simple explains automatic DNS, bypass and WARP changes before launch and
  displays actual applied settings. Cancel/failure never implies no changes.
- Existing backup/restore is reachable in Simple without factory reset. Copy
  explains that network memories persist and Simple reselects its bundled path.
- Neutral measurement states use an information symbol; real problems retain
  warning treatment.
- At 360dp/font2, all four navigation labels, actions and 48dp targets remain;
  a two-column fallback uses at most 112dp when those labels fit two columns.
- New and changed strings cover all ten locales. The generated translation
  manifest passes its exact CI checker and remains synchronized after UI edits.
- Regression tests, static analysis, app/service lint, affected screenshots,
  app builds, architecture metadata and task/OpenSpec checks pass. Inspect
  light/dark, 360dp and font2 on a leased emulator. Record CI separately.

## Ownership

- Settings worker, isolated RIPDPI-ui-stage-three-settings: SettingsPreferencesScreen,
  SettingsPreferenceSections and focused settings tests. No new catalog observer.
- Simple worker, isolated RIPDPI-ui-stage-three-simple: SimpleHomeScreen and
  related Simple presentation, BackupRestoreScreen's optional reset visibility,
  and focused tests. No core diagnostics, seed, storage or service changes.
- Navigation worker, isolated RIPDPI-ui-stage-three-navigation: BottomNavBar,
  RipDpiDegradationStrip and their focused tests. Keep existing IDs and roles.
- Integration owner: all locale resources, shared test tags, DESIGN.md,
  docs/design-system.md, translation manifest, automation, planning and combined
  gates. These serialized files have one writer.
- A golden specialist may update only a fixture family explicitly approved by
  the user after actual/diff review, in a separate worktree. No blessing is
  authorized by this task record alone. Other baselines stay fixed.

## Design and limits

RDS references: components-settings-row.html, components-bottom-nav.html and
vpn-degradation-strip.html. Preview HTML stays read-only. The deliberate compact
fallback uses full text labels with no decorative icons; this keeps destinations
visible without a chooser or smaller font. The existing theme and targets remain.
Exact diagnostic Undo is outside this presentation change. Backup restores its
existing configuration payload, not per-network memory or all Simple startup
choices. No performance optimization or claim precedes release trace evidence.
