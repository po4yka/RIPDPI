---
title: Add reset-all-settings action with confirmation and restart
type: task
status: backlog
area: data
priority: low
owner: unassigned
parent: epic-settings-backup-and-restore
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [ ] #task Add reset-all-settings action with confirmation and restart #repo/RIPDPI #area/data #status/backlog 🔽

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-reset-all-settings-action-with-confirmation-and-restart`
- **Verify:** `just test-module core:data:settings`
- **Scope (only modify these + this file + the ledger):** `app/**`, `core/data/settings/**`, `core/data/runtime-state/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add a "Reset all settings" destructive action in Tools → Backup & Restore that wipes profiles, groups, routes, user settings, and caches; then restarts the app.

## Context

Complements export/import: gives a clean slate for testing. This is a destructive action, so the confirmation must be typed (not a single tap) and the action must surface telemetry so diagnostics can distinguish "reset" from "crash" in user reports.

## Acceptance criteria

- [ ] Action surfaces behind a "type RESET to confirm" dialog (localized).
- [ ] On confirm, wipes: ProxyEntity/ProxyGroup/Subscription, RuleEntity, AppSettings proto, DiagnosticsDatabase tables that hold user history, cache directories.
- [ ] Keeps: app install state, keystore entries needed for the next session bootstrap, permission grants.
- [ ] Emits a one-shot telemetry event "user_initiated_reset" before wipe; the event is preserved across restart.
- [ ] `ProcessPhoenix`-equivalent restart brings the app to onboarding.
- [ ] Destructive action can be cancelled up to the confirm step without side effects.

## Source references

**Reference implementation notes:**:

- `app/src/main/java/io/nekohasekai/sagernet/ui/BackupFragment.kt` — the "Reset settings" action: clears `DataStore.configurationStore`, `SagerDatabase` tables, then `ProcessPhoenix.triggerRebirth()`. Single-tap with a plain yes/no dialog.

**Adapt:** Wipe-then-restart pattern, ProcessPhoenix usage. **Improve over reference implementation:** add the typed-confirmation input (user must type "RESET") and the pre-wipe telemetry event. Reference implementation's single-tap is too easy to trigger accidentally — a real user-pain report in reference implementation issue tracker.

## Links

- [[Epic - Settings backup and restore]]
