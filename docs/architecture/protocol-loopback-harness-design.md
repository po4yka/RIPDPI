# Protocol Loopback Harness — Design

> Status: **scaffold implemented; per-protocol loopbacks pending in this crate**. Authored: 2026-05-15, refreshed 2026-05-28 against `native/rust/crates/ripdpi-protocol-loopback` and the current `local-network-fixture` loopbacks.

## Why this design exists

Four backlog tasks all need the same piece of infrastructure: an in-process loopback server for each of the eight protocol crates so tests can run client + server back-to-back without any network or upstream dependency.

- `docs/tasks/issues/add-shadowtls-loopback-test-server-for-soak-runs.md`
- `docs/tasks/issues/add-quic-path-mtu-discovery-regression-test.md`
- `docs/tasks/issues/add-protocol-throughput-benchmarks-for-each-transport.md`
- `docs/tasks/issues/add-protocol-cross-stack-chain-tests-vless-over-xhttp-over-reality.md`

Building one harness shared across these four tasks is much cheaper than building four bespoke ones.

## Shape

The dev-only crate `ripdpi-protocol-loopback` now exists under `native/rust/crates/`. It is a workspace crate marked `publish = false`; it currently provides the shared `ProtocolLoopbackServer` trait and a plain-TCP `EchoLoopback` test fixture. Protocol-specific loopbacks are still pending in this crate, and the crate has no in-tree consumers today.

Do not confuse this scaffold with `local-network-fixture`: that crate now carries several protocol-specific fixtures used by current tests, including `AnyTlsLoopback`, `TrojanLoopback`, `ShadowsocksLoopback`, `NaiveH2PaddingFixture`, and MASQUE HTTP/2 CONNECT-UDP fixtures. New work should either extend those existing fixtures or deliberately move shared loopback code into `ripdpi-protocol-loopback`; do not assume this design doc's 2026-05-15 plan is the only active harness path.

```
ripdpi-protocol-loopback/
├── Cargo.toml
└── src/
    └── lib.rs             # ProtocolLoopbackServer + EchoLoopback
```

The current trait exposes:

```rust
pub trait ProtocolLoopbackServer: Send {
    fn local_addr(&self) -> SocketAddr;
    fn protocol_id(&self) -> &'static str;
}
```

`EchoLoopback::start(max_bytes_per_connection)` starts the plain-TCP echo fixture and owns shutdown via an explicit `shutdown()` method or `Drop`.

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

1. ~~Land the `ripdpi-protocol-loopback` crate scaffold~~ — done; the crate contains `ProtocolLoopbackServer` and `EchoLoopback`.
2. Add the first protocol module, likely `tuic.rs`, to drive the QUIC PMTU task.
3. Then `hysteria2.rs` to share the Quinn endpoint.
4. Then the higher-cover-handshake protocols (VLESS, xHTTP, ShadowTLS).
5. Finally MASQUE and ws-tunnel which need additional middleware (h3, fake-SNI gating).

Each step is a separate task with its own acceptance criteria.

## Owner

Native-runtime maintainer; pairs with whoever picks up the four follow-up tasks first.
