---
title: Add TUIC v4 fallback or explicit version detection
type: task
status: doing
area: rust-native
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-06-05
---

## Summary

`ripdpi-tuic` pins `TUIC_VERSION: u8 = 0x05` in `protocol.rs:11` and emits only v5 wire bytes. Decide whether to hard-require v5 (with a documented deprecation policy and a recognizable failure class for v4 servers) or to implement explicit version detection with v4 fallback.

## Context

EAimTY/tuic v4 and v5 differ on the wire (auth, packet framing). Some deployed servers remain on v4. The crate now has a local `TuicFailureKind::VersionUnsupported` classifier for non-v5 failure payloads, but runtime mapping from handshake failure to user-facing diagnostics remains the open part.

## Acceptance criteria

- [x] A short ADR under `docs/architecture/` documents the chosen policy: "v5 only with deprecation", "v4 fallback on negotiation failure", or "explicit user-selected version". **DONE 2026-05-15:** decision is **v5 only**; see `docs/architecture/tuic-v4-policy.md`. Remaining acceptance criteria below cover the classifier wiring + tests.
- [x] (2026-05-16, TDD) If "v5 only", the failure classifier maps v4-server responses to a distinct `TuicVersionUnsupported` class with remediation text. **DONE:** `FailureClass::TuicVersionUnsupported` variant added to `ripdpi-failure-classifier::types` with `as_str() -> "tuic_version_unsupported"`. `ripdpi-tuic::classify_failure_payload` maps non-v5 leading bytes to `TuicFailureKind::VersionUnsupported`; runtime mapping inside handshake failure handling remains a follow-up.
- [ ] If "fallback", the client attempts v5 first and falls back to v4 only on a recognizable rejection signature; both paths are covered by unit tests.
- [ ] If "user-selected", the config exposes `tuic_version: 4 | 5` and refuses unknown values.

## Definition of done

- v4-server connection attempts produce a user-actionable diagnostic, not a generic protocol error.

## Risks / open questions

- v4 wire is a substantially different codepath; "fallback" carries a meaningful implementation cost. "v5 only" is the cheap path.

## Links

- [[introduce-protocol-version-enum-and-version-probe-diagnostic]]

## Work log

- 2026-06-05: NOT done — Definition of Done ("v4-server connection attempts produce a user-actionable diagnostic") is unmet. The classifier scaffolding exists (ADR `docs/architecture/tuic-v4-policy.md`; `FailureClass::TuicVersionUnsupported` in `ripdpi-failure-classifier/src/types.rs`; `ripdpi-tuic::classify_failure_payload` mapping v4 bytes to `TuicFailureKind::VersionUnsupported`; `classify_probe_observation` in `ripdpi-diagnostics-protocols/src/version_probe.rs`), but NONE of it is wired into a runtime path: `classify_failure_payload` has zero external callers, `classify_probe_observation` has zero callers (dead code with unit tests only), and `FailureClass::TuicVersionUnsupported` is never constructed at runtime — it appears only in the enum def and one `response_triggers.rs` match arm that maps it to `0`. The handshake-failure→diagnostic mapping the body calls a "follow-up" still does not exist. Keep status `doing`.
