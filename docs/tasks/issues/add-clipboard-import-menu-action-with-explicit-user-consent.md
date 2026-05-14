---
title: Add clipboard-import menu action with explicit user consent
type: task
status: backlog
area: ui
priority: medium
owner: unassigned
parent: epic-qr-code-and-clipboard-profile-import
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Add clipboard-import menu action with explicit user consent #repo/RIPDPI #area/ui #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-clipboard-import-menu-action-with-explicit-user-consent`
- **Verify:** `just test-module app`
- **Scope (only modify these + this file + the ledger):** `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add an "Import from clipboard" menu action on the Configuration screen
that reads the clipboard only when the user taps it, parses via the shared
URI codec, and lands on profile-edit.

## Context

RIPDPI's privacy posture forbids silent clipboard reads. Android 12+ also
surfaces a toast for every programmatic clipboard read; only pull when the
user has made an intent explicit. No watcher, no auto-paste detection.

## Acceptance criteria

- [ ] Menu entry is visible on Configuration top-bar overflow, labeled
    "Import from clipboard".
- [ ] Tap reads clipboard once, parses via shared URI codec, and
    navigates.
- [ ] Unknown clipboard content surfaces a typed error with the scheme
    it found (no payload leak).
- [ ] No broadcast receiver, service, or foreground listener monitors
    clipboard in the background.
- [ ] On Android 12+, the system toast appearance is expected and not
    suppressed.
- [ ] Clipboard is cleared after import on user's explicit opt-in
    (default off) to reduce persisted credential exposure.

## Source references

**NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

- `app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt` — the "Import from clipboard" menu handler: `SagerNet.clipboard.primaryClip?.getItemAt(0)?.text?.toString()` then dispatches to the same URI parser used by QR scan.
- `app/src/main/java/io/nekohasekai/sagernet/SagerNet.kt` — `clipboard` accessor (wraps `ClipboardManager` as a typed system-service property). Reference for the accessor pattern only.

**Adapt:** The menu action + one-shot read + dispatch. **Skip:** NekoBox has no consent gate because it reads clipboard only on explicit user menu tap (same posture as this task asks for). NekoBox has no "clear clipboard after import" step — add it in RIPDPI as an opt-in, documented per task acceptance.

## Links

- [[Epic - QR code and clipboard profile import]]
