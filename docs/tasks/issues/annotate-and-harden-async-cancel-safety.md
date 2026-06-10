---
title: "Annotate and harden async cancel-safety in relay-core and tunnel-core"
type: task
status: todo
area: rust-native
priority: medium
owner: unassigned
parent: epic-june-2026-audit-remediation
blocks: []
blocked_by: []
created: 2026-06-10
updated: 2026-06-10
source_wiki_pages: []
linked_task: null
---

## Motivation

The 2026-06-10 async cancel-safety audit found ~9 async fns missing the project-required `# Cancel safety:` rustdoc block, one incorrect cancel-safety claim, one fairness/starvation hazard, and several not-cancel-safe sequences used inside `select!`/timeout drop boundaries. The relay session-leak bug is tracked separately (`fix-relay-core-session-leak-on-shutdown`); this task covers the annotation sweep and the two correctness hazards that are not the leak.

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

- [ ] PR confirms current state at each cited site.
- [ ] `handle_connect` no longer sends a success reply that a cancel can orphan (restructured or documented + protected); inline comment corrected.
- [ ] `handle_udp_associate` `select!` is `biased;` with `control_closed` first.
- [ ] Every async fn listed carries a `# Cancel safety:` rustdoc block.
- [ ] `async-cancel-safety` sub-agent re-run reports no un-annotated `.await` site in the two crates.
- [ ] `cargo nextest run -p ripdpi-relay-core -p ripdpi-tunnel-core --locked` green; clippy clean.

## Risks / open questions

- `splice` restructuring to drain-on-cancel is more invasive than documenting it — scope decision: document + verify cancel only fires at GC, defer the drain rewrite unless a test shows observable loss.
- Coordinate with `fix-relay-core-session-leak-on-shutdown` (the abort point introduced there must respect the `handle_connect` window).

## References

- Audit memory `project_native_audit_findings.md` (2026-06-10, items 7–8).
- `.claude/rules/llm-rust-prompts.md` (cancel-safety annotation discipline), `rust-async-internals` skill.
