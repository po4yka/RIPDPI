---
title: Add ShadowTLS v2 compatibility or document v3-only policy
type: task
status: backlog
area: rust-native
priority: low
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-15
---

- [ ] #task Add ShadowTLS v2 compatibility or document v3-only policy #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-shadowtls-v2-compatibility-or-document-v3-only`
- **Verify:** `cargo test -p ripdpi-shadowtls`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-shadowtls/**`, `docs/architecture/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

`ripdpi-shadowtls` ships v3 framing (HKDF/HMAC handshake). Some existing deployments still run v2. Decide whether to add a v2 client path or to document v3-only with a deprecation policy and a recognizable failure class.

## Context

ihciah/shadow-tls v2 and v3 diverged on handshake derivation and framing. The crate now has a local `ShadowTlsFailureKind::VersionMismatch` classifier for v2-shaped failure payloads, but runtime mapping from handshake failure to user-facing diagnostics remains the open part.

## Acceptance criteria

- [x] An ADR records the chosen policy. **DONE 2026-05-15:** v3 only; see `docs/architecture/shadowtls-version-policy.md`.
- [x] (2026-05-16, TDD) If "v3 only", the failure classifier reports `ShadowTlsVersionMismatch` distinctly from auth failures. **DONE:** `FailureClass::ShadowTlsVersionMismatch` variant added to `ripdpi-failure-classifier::types` with `as_str() -> "shadowtls_version_mismatch"`. `ripdpi-shadowtls::classify_failure_payload` maps a v2 TLS-record-at-offset-0 payload to `ShadowTlsFailureKind::VersionMismatch`; runtime mapping inside handshake failure handling remains a follow-up.
- [ ] If "v2 supported", the config exposes `shadowtls_version: 2 | 3` and both wire paths are covered by tests.

## Definition of done

- v2-server connection attempts produce a user-actionable diagnostic.

## Risks / open questions

- v2 is end-of-life upstream; "v3 only" is the recommended posture unless there's evidence of meaningful v2 server population.

## Links

- [[introduce-protocol-version-enum-and-version-probe-diagnostic]]
