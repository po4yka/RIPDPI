---
title: Add reset-all-settings action with confirmation and restart
type: task
status: done
area: data
priority: low
owner: unassigned
parent: epic-settings-backup-and-restore
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-29
---

- [x] #task Add reset-all-settings action with confirmation and restart #repo/RIPDPI #area/data #status/done 🔽

## Summary

Add a "Reset all settings" destructive action in Tools → Backup & Restore that wipes profiles, groups, routes, user settings, and caches; then restarts the app.

## Context

Complements export/import: gives a clean slate for testing. This is a destructive action, so the confirmation must be typed (not a single tap) and the action must surface telemetry so diagnostics can distinguish "reset" from "crash" in user reports.

## Acceptance criteria

- [x] Action surfaces behind a "type RESET to confirm" dialog (localized).
- [x] On confirm, wipes: ProxyEntity/ProxyGroup/Subscription, RuleEntity, AppSettings proto, DiagnosticsDatabase tables that hold user history, cache directories.
- [x] Keeps: app install state, keystore entries needed for the next session bootstrap, permission grants.
- [x] Emits a one-shot telemetry event "user_initiated_reset" before wipe; the event is preserved across restart.
- [x] `ProcessPhoenix`-equivalent restart brings the app to onboarding.
- [x] Destructive action can be cancelled up to the confirm step without side effects.

## Source references

**Reference implementation notes:**:

- `app/src/main/java/io/nekohasekai/sagernet/ui/BackupFragment.kt` — the "Reset settings" action: clears `DataStore.configurationStore`, `SagerDatabase` tables, then `ProcessPhoenix.triggerRebirth()`. Single-tap with a plain yes/no dialog.

**Adapt:** Wipe-then-restart pattern, ProcessPhoenix usage. **Improve over reference implementation:** add the typed-confirmation input (user must type "RESET") and the pre-wipe telemetry event. Reference implementation's single-tap is too easy to trigger accidentally — a real user-pain report in reference implementation issue tracker.

## Links

- [[Epic - Settings backup and restore]]
