---
title: "Fix ripdpi-relay-core session leak on RelayRuntime::stop()"
type: task
status: todo
area: relay
priority: high
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

The 2026-06-10 async cancel-safety audit found the one P0 of the pass. `ripdpi-relay-core/src/runtime/session.rs:10` — `spawn_socks_session` calls `tokio::spawn` with **no `CancellationToken`** and **discards the `JoinHandle`**. When `RelayRuntime::stop()` flips `stop_requested` and `run_accept_loop` exits, every in-flight SOCKS5 session keeps running until its own I/O completes. There is no `JoinSet`, no token, no `abort()` path, and therefore no graceful drain and no bounded shutdown time. Sessions holding upstream connections and file descriptors leak until the process exits (at which point tokio aborts them ungracefully on runtime drop).

This is the relay analogue of the bounded-shutdown discipline already applied in `ripdpi-tunnel-core` (`io_loop/udp_assoc/shutdown.rs` cancels its token then `timeout(5s, worker.await)`).

## Proposed change

1. Thread a runtime-level `CancellationToken` into the accept loop and pass a `child_token` into `handle_client` for each spawned session.
2. Collect session `JoinHandle`s in a `JoinSet` (or `TaskTracker`) owned by `RelayRuntime`.
3. In `RelayRuntime::stop()`: signal the parent token, then `abort_all()` + a bounded `join_all().await` (e.g. 5 s grace, matching tunnel-core) so shutdown is deterministic.
4. Audit `handle_connect` / `handle_udp_associate` so the abort lands at a `.await` point that does not leave a half-written SOCKS5 reply (coordinate with `annotate-and-harden-async-cancel-safety`).

## Acceptance criteria

- [ ] PR description confirms the current state at `runtime/session.rs:10` (spawn with no token / discarded handle).
- [ ] `RelayRuntime` owns a `JoinSet`/token; `spawn_socks_session` registers each task.
- [ ] `RelayRuntime::stop()` drains in-flight sessions within a bounded timeout (no unbounded wait, no leak-until-process-exit).
- [ ] Test: start a relay, open N concurrent sessions, call `stop()`, assert all session tasks terminate within the grace window and fds are released.
- [ ] `cargo nextest run -p ripdpi-relay-core --locked` green; clippy clean.

## Risks / open questions

- Aborting mid-`copy_bidirectional` drops in-flight bytes — acceptable on shutdown, but the abort point must not strand a half-sent SOCKS5 success reply to a still-connected client.
- Decide grace-window value; reuse tunnel-core's 5 s unless relay sessions warrant different.

## References

- Audit memory `project_native_audit_findings.md` (2026-06-10, item 1).
- Cancel-safe drain precedent: `ripdpi-tunnel-core/src/io_loop/udp_assoc/shutdown.rs`.
- `.claude/rules/android-vpn-lifecycle.md` (bounded shutdown, no self-deadlock).
