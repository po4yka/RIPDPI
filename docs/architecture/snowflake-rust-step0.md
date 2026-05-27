# Native Rust Snowflake Step 0

## Scope

RIPDPI currently treats Snowflake as an external Tor pluggable transport binary named `ripdpi-snowflake`. `PluggableTransportManager` launches that binary with PT managed-proxy environment variables and sends Snowflake-specific broker/front arguments through the SOCKS per-connection argument channel while connecting to the fixed dummy target `192.0.2.1:1`.

The native replacement is a new `native/rust/crates/ripdpi-snowflake` crate with a library client and a thin PT managed-proxy binary. The implementation scope is Snowflake client only: HTTP broker rendezvous with domain fronting, SDP offer/answer exchange, WebRTC DataChannel over webrtc-rs, ICE/STUN, turbotunnel reliability via KCP and smux, multi-proxy collection through `-max`, and Tor PT managed-proxy IPC.

Non-goals for the first native crate are Snowflake proxy/server/bridge roles, AMP-cache rendezvous, SQS rendezvous, and DTLS ClientHello fingerprint parity with the Go/Pion client.

## Canonical Sources

The Go Snowflake client remains the behavioral spec. Reference Rust implementations are cross-check material only and must not override the Go client or be copied into RIPDPI.

- Canonical Snowflake Go client: `gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake`, especially `client/`, `client/lib/`, `common/messages/`, `common/encapsulation/`, and `common/turbotunnel/`.
- Canonical PT IPC: Tor `pt-spec`, especially configuration environment variables, stdout IPC messages, and per-connection SOCKS arguments.
- Reference only: Arti `arti-client/examples/snowflake.rs` shows how Arti configures an external Snowflake PT, including bridge-line parameters and `snowflake-client` process configuration.
- Reference only: webrtc-rs/rtc is the WebRTC stack candidate for the Rust port.

## Feasibility Gate

Go Snowflake uses Pion WebRTC plus `covert-dtls`. The current Go client parses `covertdtls-config` and `covertdtls-fingerprint`, and then calls `SetDTLSClientHelloMessageHook` on Pion's `SettingEngine` when mimic/randomization/fingerprint mode is enabled. RIPDPI currently passes `covertdtls-config=mimic` and `utls-imitate=hellochrome_auto` as Snowflake SOCKS arguments.

The webrtc-rs stack does not expose an equivalent WebRTC-level ClientHello rewrite hook in the inspected `webrtc`/`rtc` sources. Its DTLS client hello is generated from fixed DTLS structs: version, random, cookie, cipher-suite list, compression methods, and extensions assembled by `rtc-dtls` flight generation. It supports configurable DTLS role, SRTP profiles, replay windows, and related knobs, but that is not the same as mimicking or randomizing the raw DTLS ClientHello shape.

Go/no-go assessment: replacing the Go binary with a webrtc-rs client is feasible for functionality, but it is a likely detectability regression in adversarial networks that classify Snowflake by DTLS ClientHello fingerprint. This should be treated as a product/security gate, not an implementation detail. If native Snowflake proceeds, the README and user-facing release notes should say that v1 does not match the Go/Pion DTLS fingerprint and may be more distinguishable until webrtc-rs grows an equivalent safe ClientHello shaping hook or RIPDPI intentionally carries one.

Recommendation: no-go for a default replacement of the Go Snowflake client in censorship-sensitive builds. Conditional go is reasonable only for an opt-in native Snowflake experiment or for environments where binary size/supply-chain constraints are more important than DTLS fingerprint parity.

## Local Integration Contract

`core/service/src/main/kotlin/com/poyka/ripdpi/services/PluggableTransportManager.kt` is the fixed integration boundary. It expects:

- binary name: `ripdpi-snowflake`
- env: `TOR_PT_MANAGED_TRANSPORT_VER=1`, `TOR_PT_STATE_LOCATION=<app files>/pluggable-transports/<profile>-snowflake`, `TOR_PT_CLIENT_TRANSPORTS=snowflake`, `TOR_PT_EXIT_ON_STDIN_CLOSE=1`
- command args: none
- SOCKS bridge method: `snowflake`
- dummy target: `192.0.2.1:1`
- SOCKS per-connection args: `url=<broker>;front=<front>;utls-imitate=hellochrome_auto;covertdtls-config=mimic`

`native/pluggable-transports/sources.json` is the Gradle PT sources manifest. Today it builds `ripdpi-snowflake`, `ripdpi-webtunnel`, and `ripdpi-obfs4` from the pinned Go lyrebird source. The only allowed final integration change for this project is to point the Snowflake PT source at the in-repo Rust crate; relay-core, `RelayKind`, `RelayBackend`, `RelayNativeConfig`, and `PluggableTransportManager` are out of scope.

## Test Plan

Each implementation slice must start with a failing test and show the failing `cargo test` output before the minimal implementation.

1. Broker rendezvous fixture tests: encode a `ClientPollRequest` containing serialized SDP offer, NAT type, and optional bridge fingerprint; POST it to `/client`; when fronting is configured, assert the request URL host is the front and the HTTP `Host` header remains the broker host; decode a `ClientPollResponse` into an SDP answer and surface broker errors. Canonical source: Snowflake `client/lib/rendezvous.go`, `client/lib/rendezvous_http.go`, `common/messages/client.go`, and `doc/broker-spec.txt`.
2. WebRTC DataChannel tests: create an offer before broker negotiation, gather non-trickle ICE before sending the offer, install the remote answer, wait for DataChannel open with a timeout, filter local/private candidates unless explicitly requested, and use webrtc-rs rather than any hand-rolled DTLS/SCTP/ICE. Canonical source: Snowflake `client/lib/webrtc.go`; reference source: webrtc-rs data-channel examples.
3. Turbotunnel golden tests: generate an 8-byte `ClientID`, preserve the same logical client address across proxy churn, encode/decode stream packets with the Snowflake encapsulation length prefix, run KCP over a redialing packet connection, and open smux streams over the session. Canonical source: Snowflake `common/turbotunnel/clientid.go`, `common/turbotunnel/redialpacketconn.go`, `common/encapsulation/encapsulation.go`, and `client/lib/snowflake.go`.
4. PT managed-proxy IPC tests: parse PT env, reject incompatible versions with `VERSION-ERROR`, print `VERSION 1`, validate optional `TOR_PT_PROXY`, bind a SOCKS5 listener for `snowflake`, print `CMETHOD snowflake socks5 127.0.0.1:<port>` followed by `CMETHODS DONE`, accept stdin-close termination when `TOR_PT_EXIT_ON_STDIN_CLOSE=1`, and pass per-connection SOCKS username/password arguments into the Snowflake client config. Canonical source: Tor `pt-spec` configuration environment, IPC, shutdown, and per-connection argument sections; Snowflake `client/snowflake.go`.
5. Full client tunnel tests: connect the PT SOCKS listener to a fixture broker/proxy path, verify traffic crosses the WebRTC DataChannel through KCP/smux, support `max > 1` peers without losing the logical session, and keep offline CI deterministic. Canonical source: Snowflake `client/lib/peers.go`, `client/lib/snowflake.go`, and `common/turbotunnel/`.
6. Gradle manifest test: verify `native/pluggable-transports/sources.json` no longer sources `ripdpi-snowflake` from the Go lyrebird output once the Rust binary exists, while `ripdpi-webtunnel` and `ripdpi-obfs4` remain on their existing source unless a separate task changes them. Canonical local source: `native/pluggable-transports/sources.json` and the PT source parser/build logic in `build-logic/convention/src/main/kotlin/ripdpi.android.rust-native.gradle.kts`.
7. Snowbox E2E: run the full client against the Snowflake Docker test network as nightly/manual evidence, not regular offline CI. Canonical source: Snowflake `docker-compose.yml`, `probetest/`, and client/server/broker docs.

## TDD and Commit Gate

Every slice must be one atomic Conventional Commit with scope `snowflake` and a body citing the canonical source used for that slice. Required green gates before each slice commit are `cargo test -p ripdpi-snowflake`, `cargo clippy -p ripdpi-snowflake --all-targets -- -D warnings`, and `cargo fmt --manifest-path native/rust/Cargo.toml --all --check`; broader workspace or Gradle checks are added for the manifest and Android packaging slice.
