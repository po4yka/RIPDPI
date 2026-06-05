---
title: Investigate Hysteria2 client closing the QUIC connection after auth
type: task
status: todo
area: rust-native
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-06-05
updated: 2026-06-05
---

## Summary

When building a protocol-accurate Hysteria 2 loopback server (to bench Hysteria2
throughput), the `ripdpi-hysteria2` client was observed to **close its own QUIC
connection immediately after the HTTP/3 auth exchange**, before/while the first
proxy stream is used — surfacing as `ConnectionLost(LocallyClosed)` on
`HysteriaClient::tcp_connect`, and on the server side as the client closing the
connection with `H3_NO_ERROR` (code 256). This needs investigation: it may be a
latent client lifecycle bug, or production may rely on a timing window.

## Context

`authenticate_connection` (`native/rust/crates/ripdpi-hysteria2/src/tls_quic.rs:21-46`)
builds an `h3::client` over `connection.clone()`, sends the POST `/auth`, reads
the 233 response, then spawns a task that only `poll_close`s the h3 connection.
The `SendRequest` handle is dropped when the function returns. Dropping the last
`SendRequest` makes the h3 client begin a graceful shutdown, and `h3-quinn`
appears to close the underlying (shared) QUIC connection (`H3_NO_ERROR`). Because
Hysteria 2 carries the TCP proxy over **raw** `connection.open_bi()` streams on
that same QUIC connection (`client.rs:94`, `tcp.rs`), the h3 graceful close tears
down the proxy path.

Empirically (loopback server experiment):
- The QUIC + ALPN `h3` handshake and the 233 auth succeed.
- The server *does* receive the first raw proxy bi-stream (h3-auth and raw
  `accept_bi` can coexist on one connection).
- Microseconds later the connection is closed by the client (`H3_NO_ERROR`),
  failing the proxy stream's TCP request/response.
- Driving the server's h3 connection vs. holding it idle did not change the
  outcome — the close is client-initiated and unilateral.

## Open questions

- Is this a real bug — should the client keep the h3 `SendRequest` (or the h3
  connection) alive for the QUIC connection's lifetime so the proxy streams
  survive? Does real-world Hysteria 2 (vs. a loopback) only work because the
  relay opens + uses a proxy stream within the race window?
- Does `h3-quinn` 0.0.10 graceful shutdown necessarily call `quinn`
  `connection.close()`, or can the h3 session be closed without closing QUIC?

## Acceptance criteria

- [ ] Root-caused: confirm whether the client unconditionally closes the QUIC
      connection after auth, with a written analysis and a minimal repro.
- [ ] If a client fix is warranted (keep the h3 connection alive for the
      session), land it behind the async diff-acceptance gate, and confirm a
      proxy stream survives well past auth.

## Definition of done

- A Hysteria 2 proxy stream opened after auth stays usable for the connection's
  lifetime (no post-auth `LocallyClosed`), unblocking a Hysteria2 throughput
  loopback bench.

## Links

- [[add-protocol-throughput-benchmarks-for-each-transport]]

## Work log

- 2026-06-05: discovered while attempting the Hysteria2 throughput-bench loopback
  server. Built a quinn + `h3::server` loopback that auths (233) and accepts the
  raw proxy stream, but the client closes the QUIC connection right after auth
  (`H3_NO_ERROR`/256), so the proxy round-trip fails. Reverted the WIP server
  rather than ship a fixture that can't drive the real client. The protocol-server
  loopback for Hysteria2 is blocked on this client behavior; TUIC's loopback
  (separate TLS keying-material-export auth) remains a distinct effort.
