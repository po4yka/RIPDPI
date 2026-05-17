# VLESS Chain `connect_over` Coverage Audit

> Status: **audit complete; e2e tests still owed**. Authored: 2026-05-15. Tracking task: `docs/tasks/issues/audit-vless-chained-connect-over-relay-end-to-end-tests.md`.

## Scope of `connect_over`

`VlessRealityClient::connect_over` (in `lib.rs`) layers `Reality TLS → VLESS handshake → VisionStream` on top of an existing `AsyncRead + AsyncWrite + Send` transport. It is the second-hop primitive for chain relay.

```rust
pub async fn connect_over<S>(config: &VlessRealityConfig, transport: S, target: &str) -> io::Result<impl AsyncIo>
where
    S: AsyncIo + 'static,
```

## What is covered today

- `vless_handshake_and_wrap` is exercised indirectly by the single-hop unit tests in `wire.rs` (request encoding, response parsing).
- `reality::connect_reality_tls_over` is exercised by the Reality-layer unit tests that don't depend on a real BoringSSL peer.
- No test drives data through *two* `VlessRealityClient` instances back-to-back.

## What is not covered

- Two-hop happy path: payload integrity in both directions, framing isolation between hops.
- Two-hop failure path: error on the second hop produces a recognizable error from the outer caller (not just an opaque `io::Error`).
- Resource cleanup if hop 2 fails after hop 1 succeeds (in particular, `SSL_SESSION_*` refcount discipline; see `audit-reality-ssl-session-drop-paths-for-leak-and-double-free`).

## Recommendation

Add two integration tests under `ripdpi-vless/tests/` (or `#[cfg(test)]` module) that:

1. Run a loopback "VLESS server" stub for each hop with a known UUID and addons.
2. Drive a bidirectional payload through both hops with assertions on byte equality.
3. For the failure case, kill the hop-2 stub mid-handshake and assert the resulting error class.

The loopback stub does not need real BoringSSL Reality (the test can bypass Reality with a feature flag or by injecting a mock `SslStream`-like type). The integration with real Reality stays covered by manual smoke tests against a deployed server.

## Why no tests in this audit pass

The loopback stub work is non-trivial and overlaps with the ShadowTLS test-server task (`add-shadowtls-loopback-test-server-for-soak-runs`). It is worth considering whether the two test-server tasks share a common "protocol loopback harness" helper crate. That decision is its own follow-up; not in scope for this audit.

## Owner

Native-transport owner picks up the loopback-stub + tests as the remaining work on the chain-coverage task.
