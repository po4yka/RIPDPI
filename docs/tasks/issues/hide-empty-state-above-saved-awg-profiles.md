---
id: UIX-1795615861770408
title: Hide the empty-profile message above saved AWG profiles
kind: bug
status: review
area: ui
priority: medium
owner: claude
parent: EPC-1795615697939690
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-25
updated: 2026-11-26
spec_reason: regression-tested-single-module
status_detail: "Merged; local gates and remote CI passed (run 46009834608 on d9aa00b99f); pinned by ConnectionScreenTest; a device run remains: with only AmneziaWG servers saved, the Servers list shows no empty-state message. The Server checks screen still reads No saved profiles when only AmneziaWG servers exist."
---

## Goal

The Profiles screen shows `No saved profiles.` only when there are no relay and no AmneziaWG profiles.

## Context

Found in the 2026-11-25 on-device user-flow audit (Pixel 8 Pro, githubDebug at 66673515dd, clean install). `ProfileUtilityScreen.kt` adds the AmneziaWG section after the catalog empty state, so the screen shows `No saved profiles.` above a saved AmneziaWG row. The section header `AmneziaWG` is a hardcoded string.

## Acceptance criteria

- With only AmneziaWG profiles saved, the empty-state text is absent; a unit or screenshot test covers it.
- The AmneziaWG header comes from string resources in all ten locales.

## Ownership

One writer in a dedicated worktree owns `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/profiles/` and its tests. Locale resource sets are a serialized lane: only this writer changes them while the task is in progress..
