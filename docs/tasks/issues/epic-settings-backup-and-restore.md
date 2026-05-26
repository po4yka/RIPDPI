---
title: Epic - Settings backup and restore
type: epic
status: backlog
area: epic
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [ ] #task Epic - Settings backup and restore #repo/RIPDPI #area/epic #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `epic-settings-backup-and-restore`
- **Verify:** `all child rows in GOAL_LEDGER.md are DONE or BLOCKED`
- **Scope (only modify these + this file + the ledger):** _epic — coordination only; child tasks carry the file scope_
- **Blocked-by (must be DONE in the ledger first):** `epic-advanced-routing-rules-and-geoip-enforcement`
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Goal

Let users export and restore their RIPDPI configuration (profiles, groups, routing rules, user settings) through a controlled, redacted JSON file. reference implementation has this; RIPDPI does not, and currently pins `allowBackup="false"` explicitly.

## Why now

Two deployments want this: (a) device migration without re-entering every subscription URL; (b) pre-sanitized sharing of a diagnostic bundle with a teammate. Both need a schema that includes profiles but can redact secrets.

## Key decisions

- **Two export modes: FULL and SHARE.** FULL keeps credentials (for same- device restore); SHARE redacts UUIDs, passwords, private keys, and server addresses.
- **Schema is versioned and forward-compatible.** Unknown keys are ignored on import. Schema version bumps must describe migration.
- **SAF-only file I/O;** never write backups to a hardcoded path. Export defaults to the Downloads bucket via `CreateDocument`.
- **`allowBackup=false` stays.** This is a user-initiated export, not auto-backup. Do not re-enable Android Backup Service.
- **Partial restore allowed:** profiles, routing, settings each selectable independently.
- **Restore requires app restart** via `ProcessPhoenix`-equivalent because DataStore + Room listeners in-flight need clean reinit.

## Scope

- **In scope:** backup JSON schema, FULL and SHARE export modes, SAF export and import flows, selective restore UI, share-sheet intent for SHARE output, secret redaction rules, reset-all-settings action.
- **Out of scope:** encrypted backup files (use device-level file encryption or user-provided password in a future follow-up), cloud backup integration, incremental backup.

## Ship definition

- [ ] Tools screen exposes an "Export" action that writes a versioned JSON via SAF.
- [ ] FULL export round-trips: FULL export → full wipe → FULL import → identical profile/group/rule/setting state (verified by deep-equals).
- [ ] SHARE export redacts all secret fields per an explicit allowlist, not a blocklist. Redaction is unit-tested against every protocol bean.
- [ ] Import screen lets the user pick which subsets to restore (profiles / routing / settings); skipped subsets keep their current state.
- [ ] Import on a schema version newer than the app surfaces a typed error; the current state is never partially overwritten.
- [ ] Reset-all-settings action has a confirmation dialog and restarts the app via ProcessPhoenix-equivalent.
- [ ] Share-sheet target for SHARE output lets users hand off the file.

## Child tasks

- [[Add versioned backup JSON schema with redaction allowlist]]
- [[Add SAF export action with FULL and SHARE variants]]
- [[Add SAF import flow with selective restore]]
- [[Add share-sheet intent for redacted SHARE backups]]
- [[Add reset-all-settings action with confirmation and restart]]

## Dependencies

- Depends on: [[Epic - Subscription and profile import]] — schema includes ProxyGroup/SubscriptionBean.
- Depends on: [[Epic - Advanced routing rules and geoip enforcement]] — schema includes RuleEntity.

## Risks / open questions

- Redaction allowlist must be per-protocol and per-field; one missed field leaks credentials. Add a failing test for every new bean introduced in [[Epic - Extended outbound protocol support]].
- Sideways-compatible schema evolution is hard once shipped; pick field semantics carefully in v1.
- Android's restore-after-reinstall UX: our export is explicit, but the system restore dialog after reinstall should still be a no-op since `allowBackup=false`. Verify.

## Links

- [[ripdpi-android]]
- Child issues: 5
