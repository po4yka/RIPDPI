---
id: UIX-1795661374629256
title: Use the server term in all remaining strings
kind: feature
status: backlog
area: ui
priority: medium
owner: unassigned
parent: EPC-1795615697939690
blocked_by: []
spec_mode: required
openspec_change: uix-1795661374629256-rename-remaining-profile-strings
created: 2026-11-26
updated: 2026-11-26
---

## Goal

Every UI string that names a saved relay or AmneziaWG entry calls it a server, and every string that names DPI settings calls it a preset, in all ten locales.

## Context

Split from UIX-1795615852980919 (Manage all servers in one list). That change set the terms ("server" for relay and AmneziaWG entries, "preset" for DPI settings) and renamed the strings on the Connection sheet, the Add server menu, the dedicated editor titles, and the share action. About 170 other English strings in `app` and `core/service` still contain "profile": some mean a server (share sheets, import confirmations, subscription text, notifications), some mean a DPI preset, and some mean something else (Android work profile, diagnostics profile, Wi-Fi profile) and must stay. The design record of UIX-1795615852980919 lists the chosen terms.

## Acceptance criteria

- Each remaining English string that contains "profile" is classified as server, preset, or other, and the classification is recorded in the change design.
- Strings classified as server or preset use that term in all ten locales; strings classified as other keep their wording.
- `config/i18n/translatable-keys.txt` is regenerated and `:app:lintGithubDebug` and `:core:service:lintDebug` pass.
- Roborazzi baselines that change because of the new wording are re-recorded under the golden bless rules.

## Ownership

One writer in a dedicated worktree owns all locale resource sets in `app` and `core/service`, the translatable-keys export, and the affected Roborazzi baselines. Locale resource sets are a serialized lane: only this writer changes them while the task is in progress.
