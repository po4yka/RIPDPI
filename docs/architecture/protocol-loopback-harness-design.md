# Protocol Loopback Harness — Design

> Status: **design**. Authored: 2026-05-15.

## Why this design exists

Four backlog tasks all need the same piece of infrastructure: an in-process loopback server for each of the eight protocol crates so tests can run client + server back-to-back without any network or upstream dependency.

- `docs/tasks/issues/add-shadowtls-loopback-test-server-for-soak-runs.md`
- `docs/tasks/issues/add-quic-path-mtu-discovery-regression-test.md`
- `docs/tasks/issues/add-protocol-throughput-benchmarks-for-each-transport.md`
- `docs/tasks/issues/add-protocol-cross-stack-chain-tests-vless-over-xhttp-over-reality.md`

Building one harness shared across these four tasks is much cheaper than building four bespoke ones.

## Shape

A new dev-only crate `ripdpi-protocol-loopback` (under `native/rust/crates/`) gated by a `loopback-servers` feature flag.

```
ripdpi-protocol-loopback/
├── Cargo.toml             # dev-deps only; never linked into release
├── src/
│   ├── lib.rs             # pub use re-exports
│   ├── vless.rs           # echo-server + cover handshake
│   ├── xhttp.rs           # echoes on the xhttp transport
│   ├── hysteria2.rs       # QUIC loopback w/ Salamander
│   ├── tuic.rs            # TUIC v5 loopback
│   ├── shadowtls.rs       # v3 cover handshake + framed echo
│   ├── naiveproxy.rs      # subprocess fixture
│   ├── ws_tunnel.rs       # WS+MTProto loopback
│   └── masque.rs          # h3-CONNECT echo
```

Each module exposes:

```rust
pub struct ProtocolLoopbackServer { /* opaque */ }

impl ProtocolLoopbackServer {
    pub async fn start() -> io::Result<Self>;
    pub fn local_addr(&self) -> SocketAddr;
    /// Echoes back exactly what the client sends, framed/wrapped
    /// per the protocol. Bounded so tests can assert payload integrity.
    pub async fn run_echo(&self, max_bytes: usize) -> io::Result<()>;
    pub async fn shutdown(self);
}
```

## What this unblocks

| Task | What it needs from the harness |
|---|---|
| ShadowTLS test server | `ShadowTlsLoopback::start()` + framed echo |
| QUIC PMTU regression | `QuicLoopback::start()` + UDP socket capture for MTU injection |
| Per-transport benchmarks | `{Vless,Xhttp,Hysteria2,Tuic,Masque,WsTunnel}Loopback::start()` driving Criterion `bench_function` cases |
| Cross-stack chain tests | Compose two loopback servers (e.g. xHTTP fronting VLESS) |

## What stays out of scope

- Wire conformance against upstream. The harness validates that the client's encoder/decoder are mutually consistent; it does *not* validate that the bytes match xray-core / apernet/hysteria / EAimTY/tuic output. Upstream conformance is a separate concern (`docs/tasks/issues/add-hysteria2-salamander-obfuscation-conformance-fixtures.md`, `docs/tasks/issues/add-vless-mux-conformance-tests-against-xray-core.md`).
- Cryptographic side-channels and CVE coverage.
- Multi-stream / port-hopping soak topology — those live in the hysteria2 `port_hopping` tests directly against the encoder.

## Risks

- The harness must NOT ship in production builds. Use a `[features]` flag and `[dev-dependencies]` linkage so any accidental release-build reference fails compilation.
- BoringSSL: the VLESS loopback must use a self-signed cert with REALITY auth disabled in the loopback mode (or use a stub `SslStream` shim). A real REALITY handshake against a real server-side BoringSSL is too brittle for unit-test scope.
- TUIC and Hysteria 2 share Quinn; the loopback should reuse the in-process Quinn `Endpoint` rather than spinning up two binds.

## Sequencing

1. Land an empty `ripdpi-protocol-loopback` crate scaffold (this doc + Cargo.toml stub).
2. First module: `tuic.rs` — simplest non-async-cover-handshake server, drives the QUIC PMTU task immediately.
3. Then `hysteria2.rs` to share the Quinn endpoint.
4. Then the higher-cover-handshake protocols (VLESS, xHTTP, ShadowTLS).
5. Finally MASQUE and ws-tunnel which need additional middleware (h3, fake-SNI gating).

Each step is a separate task with its own acceptance criteria.

## Owner

Native-runtime maintainer; pairs with whoever picks up the four follow-up tasks first.
