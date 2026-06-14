---
title: "Annotate and harden async cancel-safety in relay-core and tunnel-core"
type: task
status: in-review
area: rust-native
priority: medium
owner: unassigned
parent: epic-june-2026-audit-remediation
blocks: []
blocked_by: []
created: 2026-06-10
updated: 2026-06-14
source_wiki_pages: []
linked_task: null
---

## Motivation

The 2026-06-10 async cancel-safety audit found ~9 async fns missing the project-required `# Cancel safety:` rustdoc block, one incorrect cancel-safety claim, one fairness/starvation hazard, and several not-cancel-safe sequences used inside `select!`/timeout drop boundaries. The relay session-leak-on-shutdown it was paired with is already resolved in `ripdpi-relay-core::RuntimeState` (shutdown `CancellationToken` + `TaskTracker` + `drain_sessions`, see `runtime/state.rs`); this task covers only the annotation sweep and the two correctness hazards that are not the leak.

Key sites:
- **Wrong claim** — `ripdpi-relay-core/src/socks/connect.rs:19` `handle_connect`: sends the SOCKS5 success reply (`REP=0x00`) at one `.await`, then starts `copy_bidirectional` at the next. Cancellation between them leaves the client with a confirmed CONNECT and a dead socket. The inline comment claims cancel-safe and is wrong.
- **Starvation** — `ripdpi-relay-core/src/socks/udp.rs:55` `handle_udp_associate`: `select!` without `biased;` across `control_closed` / `udp_socket.recv_from` / `udp_session.recv_from`. Sustained upstream UDP can starve the `control_closed` teardown arm indefinitely.
- **Buffer loss** — `ripdpi-tunnel-core/src/session/tcp.rs:104,136` `splice`: dropped by `select!` on cancel while `tokio::io::copy` holds read-but-unwritten bytes in its internal buffer; FIN half-close not preserved. Low practical impact (cancel fires at session GC) but real.
- **Missing annotations** — `session/udp.rs:41,76,88` (`UdpSession::connect/send_to/recv_from`), `io_loop/udp_assoc/worker.rs:17` (`create_udp_association`), `session/socks5/connect.rs:17`, `relay-core/socks/auth.rs`, and the `splice`/`run_with_proxy` pair — none carry a `# Cancel safety:` block. The audit produced draft classifications for each.

## Proposed change

1. `handle_connect`: restructure so the SOCKS5 success reply and the start of relaying are atomic from the client's perspective, OR ensure the session task is never cancelled in that window; replace the incorrect inline comment with an accurate `# Cancel safety:` block.
2. `handle_udp_associate`: add `biased;` with `control_closed` as arm 0.
3. Add `# Cancel safety:` rustdoc blocks to every async fn the audit listed, using its draft classifications (cancel-safe / NOT cancel-safe / cancel-safe-except-fairness) with the stated rationale.
4. For the not-cancel-safe SOCKS5 negotiation chains used under a cancellation scope, document the torn-protocol-state hazard and confirm the caller does not cancel mid-handshake (or wrap to avoid it).

## Acceptance criteria

- [x] PR confirms current state at each cited site. (Verified at HEAD `d3bdbf1b6`; line numbers drifted from the 2026-06-10 audit and were re-located.)
- [x] `handle_connect` no longer sends a success reply that a cancel can orphan (restructured + protected); inline comment corrected.
- [x] `handle_udp_associate` `select!` is `biased;` with the teardown arms first.
- [x] Every async fn listed carries a `# Cancel safety:` rustdoc block.
- [x] `async-cancel-safety` sub-agent re-run reports no un-annotated `.await` site in the two crates. (Plus an independent adversarial re-audit: 4/4 safety claims HOLD, 0 refuted.)
- [x] `cargo nextest run -p ripdpi-relay-core -p ripdpi-tunnel-core --locked` green (295 passed, 1 ignored env-flaky QUIC e2e); clippy `-D warnings` clean; rustfmt clean.

## Implementation notes (2026-06-14)

The orphan fix could not be local to `connect.rs`: the cancellation that orphaned
the client was the outer drop-on-cancel `select!` in `runtime/session.rs`, which
dropped the whole `handle_client` future at any `.await` — including the window
between `write_reply(0x00)` and `copy_bidirectional`. Fix:

1. **Thread the session `CancellationToken` into the handlers.** `runtime/session.rs`
   now awaits `handle_client(..., cancel)` directly (the drop-on-cancel `select!`
   is removed). `handle_client` owns cancellation: it races `cancel` around the
   pre-reply negotiation (abandon-by-drop is safe — no reply written) and passes
   the token to both sub-handlers.
2. **`handle_connect`** dials upstream under a `cancel` race (pre-reply, drop-safe),
   then writes the success reply and enters `select! { cancel.cancelled() =>
   graceful client.shutdown(), copy_bidirectional }`. No externally-observable
   drop point sits between the reply and the relay, so a confirmed `CONNECT`
   always implies the relay started; on shutdown the client sees a graceful FIN.
3. **`handle_udp_associate`** loop `select!` is now `biased;` with `cancel.cancelled()`
   as **arm 0** and control-EOF as **arm 1**. Deviation from the original "control_closed
   as arm 0" wording: because the outer drop-on-cancel was removed, this loop is the
   sole place shutdown is observed for the UDP path, so `cancel` must lead. Both
   teardown arms precede the recv arms, fully closing the starvation hazard.
4. **`splice`/`run_with_proxy`** (tunnel-core): buffer-loss-on-cancel hazard is
   DOCUMENTED only, per the scope decision — `cancel` fires only at session
   GC/teardown, never mid-stream; the drain rewrite stays deferred.
5. **New regression test** `relay_runtime_stop_drains_pre_reply_sessions_within_grace_window`
   (`src/tests/shutdown_drain.rs`): a slowloris client that never sends the greeting
   still drains within the grace window, locking the pre-reply cancellation path the
   redesign introduced.

`tokio-util` already provides `CancellationToken` at HEAD; no `Cargo.toml`/lock
change. The only handler callers are `runtime/session.rs` and `socks/auth.rs`, so
the signature change is contained.

## Risks / open questions

- `splice` restructuring to drain-on-cancel is more invasive than documenting it — scope decision: document + verify cancel only fires at GC, defer the drain rewrite unless a test shows observable loss.
- The relay shutdown-drain abort points already exist (`RuntimeState::session_cancel_token` child of the shutdown token, raced against session work in `spawn_socks_session`; `drain_sessions` joins in-flight tasks). The cancel-safety annotations must describe those existing boundaries and respect the `handle_connect` window — no new abort point is needed.

## References

- Audit memory `project_native_audit_findings.md` (2026-06-10, items 7–8).
- `.claude/rules/llm-rust-prompts.md` (cancel-safety annotation discipline), `rust-async-internals` skill.
