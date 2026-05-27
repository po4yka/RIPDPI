# Snowflake Native Rust Port Decision -- ADR

> Status: **approved no-go**. Decision date: 2026-05-27. Supersedes the Step 0 feasibility note from commit `ed9c4f968`; that commit remains in history and is not reverted.

## Decision

Decision: do **not** port Snowflake to native Rust. RIPDPI will keep the current Go `ripdpi-snowflake` binary as the Snowflake integration. This is the correct integration choice, not a temporary stopgap.

No `native/rust/crates/ripdpi-snowflake` crate should be created under the current Rust WebRTC stack. `PluggableTransportManager`, relay-core, `RelayKind`, `RelayBackend`, `RelayNativeConfig`, and the Gradle pluggable-transport manifest remain on the Go-backed integration.

## Context

RIPDPI currently treats Snowflake as an external Tor pluggable transport binary named `ripdpi-snowflake`. `PluggableTransportManager` launches that binary with PT managed-proxy environment variables and sends Snowflake-specific broker/front arguments through the SOCKS per-connection argument channel while connecting to the fixed dummy target `192.0.2.1:1`.

The rejected native replacement would have created a new Rust crate with a library Snowflake client and a thin PT managed-proxy binary. The intended scope was Snowflake client only: HTTP broker rendezvous with domain fronting, SDP offer/answer exchange, WebRTC DataChannel over webrtc-rs, ICE/STUN, turbotunnel reliability via KCP and smux, multi-proxy collection through `-max`, and Tor PT managed-proxy IPC.

The Go Snowflake client remains the behavioral spec. Reference Rust implementations are cross-check material only and must not override the Go client or be copied into RIPDPI.

- Canonical Snowflake Go client: `gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake`, especially `client/`, `client/lib/`, `common/messages/`, `common/encapsulation/`, and `common/turbotunnel/`.
- Canonical PT IPC: Tor `pt-spec`, especially configuration environment variables, stdout IPC messages, and per-connection SOCKS arguments.
- Reference only: Arti `arti-client/examples/snowflake.rs` shows how Arti configures an external Snowflake PT, including bridge-line parameters and `snowflake-client` process configuration.
- Reference only: webrtc-rs/rtc is the WebRTC stack candidate for the Rust port.

## Rationale

Go Snowflake uses Pion WebRTC plus `covert-dtls`. The current Go client parses `covertdtls-config` and `covertdtls-fingerprint`, and then calls `SetDTLSClientHelloMessageHook` on Pion's `SettingEngine` when mimic/randomization/fingerprint mode is enabled. RIPDPI currently passes `covertdtls-config=mimic` and `utls-imitate=hellochrome_auto` as Snowflake SOCKS arguments.

The inspected webrtc-rs stack does not expose an equivalent WebRTC-level ClientHello rewrite hook. Its DTLS client hello is generated from fixed DTLS structs: version, random, cookie, cipher-suite list, compression methods, and extensions assembled by `rtc-dtls` flight generation. It supports configurable DTLS role, SRTP profiles, replay windows, and related knobs, but that is not the same as mimicking or randomizing the raw DTLS ClientHello shape.

The webrtc-rs DTLS ClientHello matches the pre-hardening Pion fingerprint class that is actively blocked in Russia. Replacing the Go binary with a webrtc-rs client would therefore regress Snowflake detectability in the exact environment where Snowflake fingerprint resistance matters. Functionality alone is not enough; preserving the hardened DTLS fingerprint behavior is part of the integration contract.

## Consequences

The Go `ripdpi-snowflake` binary remains the Snowflake integration. It continues to satisfy the current `PluggableTransportManager` boundary:

- binary name: `ripdpi-snowflake`
- env: `TOR_PT_MANAGED_TRANSPORT_VER=1`, `TOR_PT_STATE_LOCATION=<app files>/pluggable-transports/<profile>-snowflake`, `TOR_PT_CLIENT_TRANSPORTS=snowflake`, `TOR_PT_EXIT_ON_STDIN_CLOSE=1`
- command args: none
- SOCKS bridge method: `snowflake`
- dummy target: `192.0.2.1:1`
- SOCKS per-connection args: `url=<broker>;front=<front>;utls-imitate=hellochrome_auto;covertdtls-config=mimic`

`native/pluggable-transports/sources.json` continues to source `ripdpi-snowflake` from the Go PT source. There is no Rust slice plan, no `ripdpi-snowflake` crate, no Gradle manifest migration, and no Snowbox E2E obligation for a Rust replacement.

## Revisit Trigger

Revisit only if the Rust WebRTC stack gains a DTLS ClientHello mimicry or shaping hook equivalent to Pion DTLS v3 plus `covert-dtls`, or if `covert-dtls` is ported to Rust.
