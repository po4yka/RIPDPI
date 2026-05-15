---
title: Add versioned backup JSON schema with redaction allowlist
type: task
status: done
area: data
priority: high
owner: unassigned
parent: epic-settings-backup-and-restore
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-16
---

- [x] #task Add versioned backup JSON schema with redaction allowlist #repo/RIPDPI #area/data #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-versioned-backup-json-schema-with-redaction-allowlist`
- **Verify:** `just test-module core:data`
- **Scope (only modify these + this file + the ledger):** `core/data/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Define a versioned JSON schema for RIPDPI backups — profiles, groups,
routing rules, user settings — with an explicit per-protocol allowlist of
fields that may ship in SHARE mode after secrets are redacted.

## Context

Schema is the contract for export/import; redaction is the contract for
sharing. Both must be explicit and unit-tested. Denial-by-default: any
field added to a new protocol bean must be enumerated in the allowlist
before it can appear in SHARE output; otherwise the export fails loudly
rather than silently leaking the new field.

## Acceptance criteria

- [ ] `backup/v1` JSON schema documented under `docs/` with field
    semantics and migration policy.
- [ ] Serializer exports the schema version, creation timestamp, and
    app version as top-level metadata.
- [ ] `SHARE` variant strips every field not on the per-protocol
    allowlist. A test matrix covers every bean type; a test fails if
    a new bean introduces a field not classified as
    `PUBLIC` / `REDACTED` / `EXCLUDED`.
- [ ] `FULL` variant keeps every field but marks the archive with a
    prominent "contains credentials" flag.
- [ ] Future schema versions must provide a forward migration; schema
    version N+1 must deserialize N by migration or reject cleanly with
    a typed "unsupported version" error.

## Source references

**Reference implementation notes:**:

- `app/src/main/java/io/nekohasekai/sagernet/ui/BackupFragment.kt` — the full export/import codepath. Schema is a Gson-serialized `Backup` object with fields: `version`, `profiles`, `groups`, `rules`, `settings` (map). **Reference the field shape**; version starts at `1`.
- Inside the same file: the `Backup.importBackup()` method shows the reverse — version gate, selective restore per category.

**Adapt:** Top-level schema shape (`version`, `profiles`, `groups`, `rules`, `settings`), category-level selectivity. **Improve over reference implementation:** reference implementation has NO redaction variant; every export contains credentials. RIPDPI must add a SHARE variant with per-protocol field allowlist — a deliberate improvement documented in acceptance criteria. **Skip:** Gson (use `kotlinx.serialization`).

## Links

- [[Epic - Settings backup and restore]]


## system-http-proxy-service
