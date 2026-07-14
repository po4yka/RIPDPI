# Protocol Loopback Harness — Design

> Status: **active shared test infrastructure**. Authored: 2026-05-15, refreshed 2026-07-14. `ripdpi-protocol-loopback` ships `EchoLoopback` (plain TCP) and `QuicLoopback` (generic QUIC echo with a self-signed certificate, `QUIC_LOOPBACK_ALPN`, and a one-call client helper). `ripdpi-hysteria2` consumes the QUIC harness for its port-hopping soak. Protocol-specific fixtures intentionally remain next to the code they exercise: ShadowTLS uses its `test-server` feature, while `local-network-fixture` owns the VLESS Reality, xHTTP Reality, AnyTLS, Trojan, Shadowsocks, Naive H2 padding, and MASQUE fixtures. The shared crate is therefore not intended to become a registry of every transport.

## Why this design exists

The harness was introduced so transport tests could run client and server back-to-back without an external network or upstream service. The original plan assumed one shared server per protocol; implementation showed that only generic TCP and QUIC primitives benefit from centralization, while wire-specific fixtures are easier to maintain beside their protocol implementation.

- `docs/tasks/issues/add-quic-path-mtu-discovery-regression-test.md`

## Shape

The dev-only crate `ripdpi-protocol-loopback` is a workspace crate marked `publish = false`. It provides the shared `ProtocolLoopbackServer` trait, `EchoLoopback`, and `QuicLoopback`; `ripdpi-hysteria2` consumes it as a dev-dependency.

Do not confuse this scaffold with `local-network-fixture`: that crate now carries several protocol-specific fixtures used by current tests, including `AnyTlsLoopback`, `TrojanLoopback`, `ShadowsocksLoopback`, `NaiveH2PaddingFixture`, and MASQUE HTTP/2 CONNECT-UDP fixtures. New work should either extend those existing fixtures or deliberately move shared loopback code into `ripdpi-protocol-loopback`; do not assume this design doc's 2026-05-15 plan is the only active harness path.

```
ripdpi-protocol-loopback/
├── Cargo.toml
└── src/
    ├── lib.rs             # ProtocolLoopbackServer + EchoLoopback
    └── quic.rs            # QuicLoopback + test-only TLS verifier
```

The current trait exposes:

```rust
pub trait ProtocolLoopbackServer: Send {
    fn local_addr(&self) -> SocketAddr;
    fn protocol_id(&self) -> &'static str;
}
```

`EchoLoopback::start(max_bytes_per_connection)` starts the plain-TCP echo fixture and owns shutdown via an explicit `shutdown()` method or `Drop`.

## Current fixture ownership

| Surface | Fixture owner |
|---|---|
| Generic TCP echo | `ripdpi-protocol-loopback::EchoLoopback` |
| Generic QUIC echo / Hysteria2 port-hopping soak | `ripdpi-protocol-loopback::QuicLoopback` |
| ShadowTLS framing | `ripdpi-shadowtls` with its `test-server` feature |
| VLESS Reality and xHTTP Reality chains | `local-network-fixture` |
| AnyTLS, Trojan, Shadowsocks, Naive H2 padding, MASQUE | `local-network-fixture` or the owning transport crate |

## What stays out of scope

- Wire conformance against upstream. The harness validates that the client's encoder/decoder are mutually consistent; it does *not* validate that the bytes match xray-core / apernet/hysteria / EAimTY/tuic output. Upstream conformance is a separate concern (`docs/tasks/issues/add-hysteria2-salamander-obfuscation-conformance-fixtures.md`, `docs/tasks/issues/add-vless-mux-conformance-tests-against-xray-core.md`).
- Cryptographic side-channels and CVE coverage.
- Multi-stream / port-hopping soak topology — those live in the hysteria2 `port_hopping` tests directly against the encoder.

## Risks

- The harness must NOT ship in production builds. Consumers must link it only through `[dev-dependencies]`.
- BoringSSL: the VLESS loopback must use a self-signed cert with REALITY auth disabled in the loopback mode (or use a stub `SslStream` shim). A real REALITY handshake against a real server-side BoringSSL is too brittle for unit-test scope.
- TUIC and Hysteria 2 share Quinn; the loopback should reuse the in-process Quinn `Endpoint` rather than spinning up two binds.

## Extension rule

Add a primitive to `ripdpi-protocol-loopback` only when at least two consumers need the same protocol-agnostic server behavior. Keep wire-specific fixtures in the owning crate or `local-network-fixture`; do not move them merely to make this crate appear comprehensive.

## Owner

Native-runtime maintainer; pairs with whoever picks up the four follow-up tasks first.
