---
id: UIX-1795661374629256
title: Use the server term in all remaining strings
kind: feature
status: review
area: ui
priority: medium
owner: unassigned
parent: EPC-1795615697939690
blocked_by: []
spec_mode: required
openspec_change: uix-1795661374629256-rename-remaining-profile-strings
created: 2026-11-26
updated: 2026-11-26
status_detail: "Merged; local gates and remote CI passed (run 46009834608 on d9aa00b99f); a device run remains: check the renamed server and preset wording on the share, import, subscription, and notification screens in a sample of locales. The Arabic, Persian, and Hindi plural and gender forms still need a native-speaker review."
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

## Evidence

- Classification: the change design lists all 192 English strings that contained "profile": 73 server, 11 preset (the DPI settings editor is now "Preset editor"), 108 other (fake-payload, TLS, diagnostics, DNS, WARP and Zero Trust, AmneziaWG shaping, Xray provider, Android work profile). `core/service` had none.
- Change: 013b3bcecc renames the 84 server and preset strings in all ten locales (90 XML files). Hiddify's own "Add profile" button name in the setup sheet stays. No key was added or removed, so `config/i18n/translatable-keys.txt` is unchanged after `scripts/ci/export-strings-for-translation.sh`.
- Tests: `RelayFieldsTest` and `OnboardingScreenTest` asserted the old English text; they now assert the new text.
- Goldens: 51 Roborazzi baselines re-recorded (text-only diffs, reviewed).
- Gates: `./gradlew :app:verifyRoborazziGithubDebug` (4326 tests), `./gradlew :app:lintGithubDebug :core:service:lintStandardDebug` and `just ci-gates` pass.
- Remote CI: run 46009834608 on main d9aa00b99f passed (35 jobs, no failures). Not checked: device run. The ar, fa and hi plural and gender forms were written without a native-speaker review.
- After rebase onto main bc95fe0e3e: `:app:lintGithubDebug :core:service:lintStandardDebug` and all Roborazzi comparisons pass. One unit test fails: `RipDpiNavHostLogicTest` "UI audit spec covers or explains every registered route" reports stale tags `logs-actions` and `logs-filters`. It passed before the rebase, this branch changes no Kotlin source or guide spec, so the failure comes from main's commits c470ca7fbc and bc95fe0e3e.
