---
title: Add SAF import flow with selective restore
type: task
status: backlog
area: data
priority: medium
owner: unassigned
parent: epic-settings-backup-and-restore
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Add SAF import flow with selective restore #repo/RIPDPI #area/data #status/backlog 🔼

## Summary

Add a SAF-based import flow that reads a backup JSON, previews its
contents, and lets the user pick which subsets (profiles / routing /
settings) to restore.

## Context

Restore is destructive; no silent overwrite. The preview step lists the
counts (N profiles, M rules, K settings changed vs current), and the
user opts in to specific subsets. Schema-version gating is strict:
newer-than-app rejects, older migrates.

## Acceptance criteria

- [ ] Import entry point in Tools → Backup & Restore.
- [ ] File picker restricts to `application/json` MIME.
- [ ] Preview screen shows per-category counts and the schema version.
- [ ] Checkbox per category (profiles+groups / routes / settings)
    selects what to restore; current state for unchecked categories
    is preserved.
- [ ] Restore writes to a staging area, validates integrity, then
    atomically swaps into the live data stores.
- [ ] `ProcessPhoenix`-equivalent restart after successful restore so
    all in-flight DataStore / Room observers reinitialize.
- [ ] Malformed JSON or failed integrity check aborts without touching
    live data.

## Source references

**NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

- `app/src/main/java/io/nekohasekai/sagernet/ui/BackupFragment.kt` — the import flow: `ActivityResultContracts.OpenDocument(arrayOf("application/json"))` → confirmation dialog (no preview) → `Backup.importBackup()` → `ProcessPhoenix.triggerRebirth()`.

**Adapt:** SAF contract, ProcessPhoenix restart pattern (use `com.jakewharton:process-phoenix:2.1.2` — same version). **Improve over NekoBox:** NekoBox confirmation is a plain "yes/no" dialog without preview; RIPDPI's acceptance criteria adds per-category preview counts and opt-in selectivity. **Skip:** NekoBox's all-or-nothing restore pattern.

## Links

- [[Epic - Settings backup and restore]]
- [[Add versioned backup JSON schema with redaction allowlist]]
