---
id: EPC-1786264762917503
title: Epic - June 2026 full-project audit remediation
kind: epic
status: todo
area: epic
priority: medium
risk: high
owner: Architecture maintainer
parent: null
blocked_by:
  - CIC-1786272446167159
  - RLY-1786264762917178
  - RST-1786264762917099
spec_mode: required
openspec_change: epc-1786264762917503-epic-june-2026-audit-remediation
created: 2026-06-10
updated: 2026-08-09
source_wiki_pages: []
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
- ✅ `fix-relay-core-session-leak-on-shutdown` — P0 spawned-session leak in `ripdpi-relay-core`.
  **Resolved — verified at HEAD 2026-06-14 (no code change needed; already landed).**
  Shutdown path is fully wired: `RelayRuntime::stop()` → `RuntimeState::request_stop()` cancels
  the parent `shutdown_token` (`runtime/state.rs`); every session is spawned on a `TaskTracker`
  with a child cancel token and a biased `tokio::select!` on `cancel.cancelled()`
  (`runtime/session.rs::spawn_socks_session`, the sole spawn site via the accept loop in
  `runtime/listener.rs`); `RelayRuntime::run()` joins via `drain_sessions(SESSION_DRAIN_GRACE = 5s)`
  and records an error if the grace window is exceeded (`runtime.rs`). Reproduce-before-fix test
  `tests/shutdown_drain.rs::relay_runtime_stop_drains_in_flight_sessions_within_grace_window`
  (3 idle SOCKS5 sessions over a real Shadowsocks loopback backend; asserts `run()` returns
  in-window, `active_sessions()==0`, and client fds are released). Evidence: `cargo nextest run -p
  ripdpi-relay-core --locked` → 87/87 passed incl. that test.
- ✅ `redact-raw-bssid-in-detection-findings` — privacy violation in `LocationSignalsChecker`.
  **Resolved — verified at HEAD 2026-06-14 (no code change needed; already landed).**
  `LocationSignalsChecker.evaluate()` emits only a presence flag — `BSSID: present` /
  `BSSID: unavailable` / `BSSID: permission not granted` — never the raw value, with an inline
  comment citing `.claude/rules/network-fingerprint-privacy.md`; the raw `bssid` field feeds only
  `isUsableBssid()`. Regression test `LocationSignalsCheckerTest.raw BSSID never appears in a
  Finding string` asserts no Finding contains the raw BSSID (any encoding) and that a usable BSSID
  surfaces as `BSSID: present`; `placeholder BSSID is reported as unavailable` covers the sentinel.
  Adjacent-checker sweep (all 22 checkers): no other checker interpolates a raw BSSID/SSID/MAC into
  a Finding; `BeaconDbClient` surfaces only fixed status strings (`exact match` / `lookup failed
  <code>` / …) — AP MACs go only into the outbound geolocate request body, never a Finding; no raw
  BSSID is logged anywhere. Evidence: `:core:detection:testDebugUnitTest --tests
  *LocationSignalsCheckerTest` → BUILD SUCCESSFUL.

**Medium — Rust correctness**
- ✅ `fix-panic-in-drop-exit-ip-cap-guard` — **Verified resolved at HEAD 2026-06-14 (no change needed).**
  `ripdpi-proxy-runtime/src/exit_ip_cap.rs` `Drop` is panic-free: locks via
  `unwrap_or_else(std::sync::PoisonError::into_inner)` and decrements with `saturating_sub(1)`;
  test `guard_drops_cleanly_when_mutex_is_poisoned` covers it. `cargo nextest -p
  ripdpi-proxy-runtime --locked`: 233/233.
- ✅ `fix-socks5-core-panic-and-credential-truncation` — **Verified resolved at HEAD 2026-06-14
  (no change needed; landed in f23d15f3c / da297e25c).** `socks4::ReplyError::as_u8` returns
  `Option<u8>` (no `panic!`/unreachable on the parse path); SOCKS4 domain length is bounds-checked
  (→ typed error, no buffer-overflow panic); SOCKS5 `use_password_auth` rejects >255-byte
  credentials instead of truncating. Tests: `domain_too_long_returns_error`,
  `use_password_auth_rejects_oversized_username`/`_password`, `oversized_credential_writes_no_bytes`.
  `cargo nextest -p ripdpi-socks5-core --locked`: 27/27.
- ✅ `restore-discarded-adaptive-routing-feedback` — **Landed 2026-06-14.** The TLS branch of
  `record_failure_feedback` (`ripdpi-proxy-runtime/.../routing/failure/feedback.rs`) was the last
  feedback call still discarding its result with `let _ =`; switched to `?` for parity with its
  sibling calls and the UDP-path fix. Honest scope: the wrapper is infallible, so the signal
  already reached the direct-path learner — this is error-propagation consistency **plus** the
  regression-lock test `tls_post_client_hello_failure_drives_learner_state` (asserts a TLS
  post-client-hello failure → TCP success emits `TCP_POST_CLIENT_HELLO_FAILURE_TCP_OK`), the
  parity coverage the routing/failure module lacked vs the UDP path. `cargo nextest -p
  ripdpi-proxy-runtime --locked`: 233/233; `clippy -D warnings`: clean.
- `annotate-and-harden-async-cancel-safety`
- ✅ `recover-monitor-coordinator-worker-panic` — **Verified resolved at HEAD 2026-06-14 (no change
  needed).** `ripdpi-monitor-engine/src/engine/runtime/panic_recovery.rs` classifies a worker's
  `thread::Result` `Err` into a `Panicked` outcome and `handle_panicked_runner` builds synthetic
  recorded steps so one worker panic is contained + diagnosable rather than killing the scan; test
  `parallel_runner_panic_is_isolated_and_scan_completes_with_siblings`. `cargo nextest -p
  ripdpi-monitor-engine --locked`: 156/156.

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

- ✅ Both `high` tasks landed with tests (relay shutdown drains within a bounded timeout; no raw BSSID reachable in any serialized `Finding`/log). **Met — verified at HEAD 2026-06-14; see the High child entries above for evidence.**
- All `medium` Rust correctness tasks landed or explicitly deferred with rationale in their work log.
- Architecture tasks either landed or moved to `NATIVE_RUST.md`-documented backlog with a CI growth guard.
- This epic flips to `done` (file deleted) when every child is `done`/`dropped`.

## References

- Audit memory: `~/.claude/projects/-Users-po4yka-GitRep-RIPDPI/memory/project_native_audit_findings.md` (2026-06-10 section).
- `.claude/rules/network-fingerprint-privacy.md`, `android-vpn-lifecycle.md`, `llm-rust-prompts.md`.
- `docs/architecture/NATIVE_RUST.md` (crate taxonomy, prune candidates).
