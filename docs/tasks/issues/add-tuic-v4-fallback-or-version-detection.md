---
title: Add TUIC v4 fallback or explicit version detection
type: task
status: backlog
area: rust-native
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-15
---

- [ ] #task Add TUIC v4 fallback or explicit version detection #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-tuic-v4-fallback-or-version-detection`
- **Verify:** `cargo test -p ripdpi-tuic`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-tuic/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

`ripdpi-tuic` pins `TUIC_VERSION: u8 = 0x05` in `protocol.rs:11` and emits only v5 wire bytes. Decide whether to hard-require v5 (with a documented deprecation policy and a recognizable failure class for v4 servers) or to implement explicit version detection with v4 fallback.

## Context

EAimTY/tuic v4 and v5 differ on the wire (auth, packet framing). Some deployed servers remain on v4. A v4 server today produces an opaque connect failure with no signal to the user that the version is the problem.

## Acceptance criteria

- [x] A short ADR under `docs/architecture/` documents the chosen policy: "v5 only with deprecation", "v4 fallback on negotiation failure", or "explicit user-selected version". **DONE 2026-05-15:** decision is **v5 only**; see `docs/architecture/tuic-v4-policy.md`. Remaining acceptance criteria below cover the classifier wiring + tests.
- [x] (2026-05-16, TDD) If "v5 only", the failure classifier maps v4-server responses to a distinct `TuicVersionUnsupported` class with remediation text. **DONE:** `FailureClass::TuicVersionUnsupported` variant added to `ripdpi-failure-classifier::types` with `as_str() -> "tuic_version_unsupported"`. Two new tests: `tuic_version_unsupported_distinct_from_connect_failure` + extended `failure_class_as_str_covers_all_variants`. Wiring the actual v4-response detection inside `ripdpi-tuic::client` remains a follow-up but the typed class now exists to map to.
- [ ] If "fallback", the client attempts v5 first and falls back to v4 only on a recognizable rejection signature; both paths are covered by unit tests.
- [ ] If "user-selected", the config exposes `tuic_version: 4 | 5` and refuses unknown values.

## Definition of done

- v4-server connection attempts produce a user-actionable diagnostic, not a generic protocol error.

## Risks / open questions

- v4 wire is a substantially different codepath; "fallback" carries a meaningful implementation cost. "v5 only" is the cheap path.

## Links

- [[introduce-protocol-version-enum-and-version-probe-diagnostic]]
