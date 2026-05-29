---
title: Add share-sheet intent for redacted SHARE backups
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

- [x] #task Add share-sheet intent for redacted SHARE backups #repo/RIPDPI #area/data #status/done 🔽 ✅ 2026-05-29

## Summary

Add a "Share diagnostic backup" shortcut that generates a SHARE-variant backup on-demand and hands it to the Android share sheet.

## Context

For remote debugging, a user can send a redacted backup to a maintainer without keeping a file on disk. The file is written to the cache dir, shared via `FileProvider`, and cleaned up after the intent completes.

## Acceptance criteria

- [x] Shortcut in Tools → Backup & Restore labeled "Share redacted backup".
- [x] Invocation generates a fresh SHARE backup, writes to cache dir, and launches `ACTION_SEND` with `FileProvider` URI.
- [x] MIME is `application/json`; subject is predictable; message body is empty to avoid accidental leaks from autofill.
- [x] File is deleted after the share completes or is cancelled (hook into the result callback).
- [x] First-run shows a one-time reminder that SHARE is redacted but not zero-knowledge.

## Source references

**Reference implementation notes:** — no direct analog. Reference implementation's `BackupFragment.kt` has a "share" path but it shares the full-credentials backup, which is the exact footgun this task is designed to prevent.

**amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`):

- `ui/src/main/java/org/amnezia/awg/activity/LogViewerActivity.kt` — FileProvider authority pattern `${applicationId}.exported-log` with `grantUriPermissions=true`. Reuse this pattern for the backup FileProvider (authority e.g. `ripdpi.backup.fileprovider`).
- The `ShareCompat.IntentBuilder` usage there is the cleanest template.

**Adapt:** FileProvider authority + grant-uri-permission setup, `ShareCompat` builder usage. **Add (neither project has):** post-share cleanup of the cache-dir temp file on intent result.

## Links

- [[Epic - Settings backup and restore]]
