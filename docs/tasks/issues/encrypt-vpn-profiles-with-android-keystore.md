---
title: Encrypt VPN profiles with Android Keystore
type: task
status: backlog
area: vpn
priority: high
owner: unassigned
parent: epic-fail-closed-android-vpn-policy-engine
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-01
---

- [ ] #task Encrypt VPN profiles with Android Keystore #repo/RIPDPI #area/vpn #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `encrypt-vpn-profiles-with-android-keystore`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`, `core/data/settings/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Store VPN profiles, subscription state, and credential-bearing rule metadata encrypted with an Android Keystore-backed key and internal storage only.

## Motivation

tunneled outbound profile profiles contain bearer credentials. They must not live in plaintext SharedPreferences, external storage, logs, screenshots, or crash reports.

## Scope

- In scope: Keystore key management, encrypted profile blobs, migration from plaintext fields if any exist, redacted secret wrappers, and storage tests.
- Out of scope: cloud backup integration and server-side secret management.

## Acceptance criteria

- [ ] Profile and subscription credential blobs are encrypted at rest in internal app storage.
- [ ] Android Keystore holds the key-encryption key and uses StrongBox when available and configured.
- [ ] Secret wrapper types redact `toString()`, equality debug output, logs, and diagnostics.
- [ ] Migration path can import existing plaintext development profiles and remove plaintext copies.
- [ ] Tests prove exported diagnostics contain config hashes or fingerprints, not raw credentials.

## Design notes

Public values can still become sensitive when combined with endpoints. Keep profile export and diagnostics conservative even for public keys.

## Risks / open questions

- StrongBox availability and authentication requirements vary by device; default should not make normal VPN startup fragile.

## Links

- [[Epic - Fail-closed Android VPN policy engine]]
- [[Epic - Privacy and diagnostics]]
- https://developer.android.com/privacy-and-security/keystore
