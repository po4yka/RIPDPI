---
title: Add SAF export action with FULL and SHARE variants
type: task
status: backlog
area: data
priority: medium
owner: unassigned
parent: epic-settings-backup-and-restore
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [ ] #task Add SAF export action with FULL and SHARE variants #repo/RIPDPI #area/data #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-saf-export-action-with-full-and-share-variants`
- **Verify:** `just test-module app`
- **Scope (only modify these + this file + the ledger):** `app/**`, `core/data/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add Tools-screen export action that writes the backup JSON via
`ActivityResultContracts.CreateDocument`, letting the user pick between
FULL (credentials included) and SHARE (redacted) variants.

## Context

SAF is the only write path — no hardcoded file locations. Default target
is the Downloads bucket, default filename is
`ripdpi-backup-YYYY-MM-DDTHH-MM.json`. The FULL/SHARE picker is a
bottom-sheet with clear risk framing for FULL.

## Acceptance criteria

- [ ] Export entry point in Tools → Backup & Restore.
- [ ] Variant picker makes the risk visually distinct; FULL is not the
    default.
- [ ] Writer streams the JSON via SAF `OutputStream`; never materializes
    the full archive in memory.
- [ ] On success, a snackbar confirms the destination and offers a
    "Share" follow-up for SHARE variant; for FULL, share is not
    offered inline.
- [ ] Write failure surfaces a typed error; partial file is deleted if
    the user hit cancel mid-write.
- [ ] Export never logs the payload; only the byte count and variant.

## Source references

**Reference implementation notes:**:

- `app/src/main/java/io/nekohasekai/sagernet/ui/BackupFragment.kt` — the export flow uses `ActivityResultContracts.CreateDocument("application/json")`. Search for `exportLauncher` registration.
- Default filename pattern: `reference-implementation_backup_<timestamp>.json`. RIPDPI should use `ripdpi-backup-<timestamp>.json`.

**amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`) — **has a superior gating pattern**:

- `ui/src/main/java/org/amnezia/awg/preference/ZipExporterPreference.kt` — **biometric-gated** export, plus MDM-policy suppression via `AdminKnobs.disable_config_export`. **Adopt both patterns** in RIPDPI: biometric gate for FULL variant, optional MDM suppression.

**Adapt:** SAF contract, filename pattern. **Adopt from AWG:** biometric gate for FULL, MDM suppression knob. **Skip:** Reference implementation's zero-gate export (credentials-to-any-picker is a privacy footgun).

## Links

- [[Epic - Settings backup and restore]]
- [[Add versioned backup JSON schema with redaction allowlist]]
