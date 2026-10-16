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
  SettingsPreferenceSections, SettingsRow chevron forwarding and their focused
  tests. No new catalog observer. SettingsRow has this one exclusive writer.
- Simple worker, isolated RIPDPI-ui-stage-three-simple: SimpleHomeScreen and
  related Simple presentation, BackupRestoreScreen's optional reset visibility,
  and focused tests. No core diagnostics, seed, storage or service changes.
- Navigation worker, isolated RIPDPI-ui-stage-three-navigation: BottomNavBar,
  RipDpiDegradationStrip and their focused tests. Keep existing IDs and roles.
- CI repair worker, isolated RIPDPI-ui-stage-three-ci-tests: ConfigRuntimePresentation
  lifecycle effect collection and ConfigModeChipsLayoutTest resource expectations.
  Keep the lifecycle guard and whole-label/selection/callback assertions.
- Integration owner: all locale resources, shared test tags, DESIGN.md,
  docs/design-system.md, translation manifest, automation, planning and combined
  gates, and the InstalledReadabilityScreenshotTest theme viewport. These
  serialized files have one writer.
- A golden specialist may update only a fixture family explicitly approved by
  the user after actual/diff review, in a separate worktree. No blessing is
  authorized by this task record alone. Other baselines stay fixed.
  The user approved 15 PNG on 2026-10-16: all nine RoborazziSimpleHomeScreenTest
  images, ModeEditorScreenshotTest.saving light/dark, and catalog modeEditorScreen
  plus onboardingPersona/Validation/ModeMaximumFontScreen. This isolated
  RIPDPI-ui-stage-three-approved-goldens lane owns only those fixtures.
  A second explicit approval on 2026-10-16 covers exactly 22 PNG: maximum
  accessibility navigation in English, Spanish and French (six), maximum-font
  Settings and Diagnostics shells (two), catalog Settings medium/dark/large/max
  and onboarding DNS (five), Arabic/Persian/Chinese Settings (three), and all six
  Russian maximum-font theme previews at 1600dp. The same isolated specialist
  owns this second lane on fix/ui-stage-three-accessibility-goldens. Other
  fixture families remain read-only.

## Device follow-up ownership

- Navigation worker, new isolated RIPDPI-ui-stage-three-arabic-navigation:
  BottomNavBar and focused tests only. Reproduce and repair the real Arabic
  360dp/font2 height of 114dp against the existing 112dp contract. Keep full
  labels and 48dp targets. No exception to the normative height requirement.
- Simple worker, new isolated RIPDPI-ui-stage-three-backup-insets:
  SimpleHomeScreen backup overlay insets and focused tests only. Keep the Back
  target below status-bar bounds without duplicate insets in other overlays.
- Device specialist owns only temporary harness and acceptance artifacts.
  Fix fixed-navigation viewport and untagged scroll-progress observations;
  preserve failed-run evidence. Repeat all cases on rebuilt APKs after fixes.

## Design and limits

RDS references: components-settings-row.html, components-bottom-nav.html and
vpn-degradation-strip.html. Preview HTML stays read-only. The deliberate compact
fallback uses full text labels with no decorative icons; this keeps destinations
visible without a chooser or smaller font. The existing theme and targets remain.
Exact diagnostic Undo is outside this presentation change. Backup restores its
existing configuration payload, not per-network memory or all Simple startup
choices. No performance optimization or claim precedes release trace evidence.
