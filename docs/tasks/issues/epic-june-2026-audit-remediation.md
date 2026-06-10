---
title: "Epic - June 2026 full-project audit remediation"
type: epic
status: doing
area: epic
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-06-10
updated: 2026-06-10
source_wiki_pages: []
linked_task: null
---

## Goal

Remediate the findings from the 2026-06-10 full-project audit (six parallel specialized passes: Rust API quality, unsafe code, async cancel-safety, JNI boundary, Kotlin/Android design, and architecture layering) across the ~112-crate native Rust workspace and the Android app. Close the one real shutdown bug, the one privacy-rule violation, and the cluster of medium-severity correctness and structural issues, while preserving the confirmed-healthy posture (no UB, no JNI signature mismatches, no circular deps, protect() invariant intact).

## Why now

The audit confirmed the codebase is in good structural health but surfaced a P0 resource leak (relay sessions never drained on shutdown) and a privacy regression (raw BSSID interpolated into a detection `Finding`). Both are small, well-bounded diffs. The remaining mediums are accumulating debt — god ViewModels regrew after the `MainViewModel` win, Hilt singleton count doubled, and two new layering violations plus two undocumented orphan crates appeared since `NATIVE_RUST.md` was last refreshed. Capturing them now prevents re-litigation in the next audit (findings recorded in agent memory `project_native_audit_findings.md`).

## Key decisions

- One task file per coherent fix unit, not one per raw finding — related findings in the same crate/file are bundled (e.g., the two SOCKS5-core panics, the cancel-safety annotation sweep).
- Severity → priority: the relay leak and BSSID redaction are `high`; the rest are `medium`/`low`.
- Each task confirms the current state in its PR description before fixing — audit findings are point-in-time (2026-06-10) and the cited file:line may drift.
- High-risk shared files (Cargo.lock, wire schemas, locale strings, goldens) stay in a single serialized lane per the worktree workflow; most of these tasks touch isolated crates and can proceed in parallel.

## Scope

Child tasks (this epic is `parent:` for each):

**High**
- `fix-relay-core-session-leak-on-shutdown` — P0 spawned-session leak in `ripdpi-relay-core`.
- `redact-raw-bssid-in-detection-findings` — privacy violation in `LocationSignalsChecker`.

**Medium — Rust correctness**
- `fix-panic-in-drop-exit-ip-cap-guard`
- `fix-socks5-core-panic-and-credential-truncation`
- `restore-discarded-adaptive-routing-feedback`
- `annotate-and-harden-async-cancel-safety`
- `recover-monitor-coordinator-worker-panic`

**Medium — JNI / unsafe**
- `harden-jni-callback-thread-attach-and-null-sentinels`
- `centralize-unsafe-javavm-from-raw-and-signal-cast`

**Medium — Android design**
- `decompose-god-viewmodels-blockcheck-detection-backup`
- `introduce-vpn-session-hilt-scope`
- `fix-launchedeffect-unit-session-keyed-refresh`

**Medium/Low — architecture & API surface**
- `introduce-ws-transport-port-to-fix-layer-violations`
- `split-policyport-trait-selection-learning`
- `reduce-pub-surface-monitor-engine-and-config`
- `guard-relaybackend-quic-snapshot-exhaustiveness`
- `triage-undocumented-orphan-diagnostics-crates`

## Ship definition

- Both `high` tasks landed with tests (relay shutdown drains within a bounded timeout; no raw BSSID reachable in any serialized `Finding`/log).
- All `medium` Rust correctness tasks landed or explicitly deferred with rationale in their work log.
- Architecture tasks either landed or moved to `NATIVE_RUST.md`-documented backlog with a CI growth guard.
- This epic flips to `done` (file deleted) when every child is `done`/`dropped`.

## References

- Audit memory: `~/.claude/projects/-Users-po4yka-GitRep-RIPDPI/memory/project_native_audit_findings.md` (2026-06-10 section).
- `.claude/rules/network-fingerprint-privacy.md`, `android-vpn-lifecycle.md`, `llm-rust-prompts.md`.
- `docs/architecture/NATIVE_RUST.md` (crate taxonomy, prune candidates).
