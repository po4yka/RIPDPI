# Snowflake Native Rust Port Decision -- ADR

> Status: **approved no-go**. Decision date: 2026-05-27. Supersedes the Step 0 feasibility note from commit `ed9c4f968`; that commit remains in history and is not reverted.

## Decision

Decision: do **not** port Snowflake to native Rust. RIPDPI uses the official Go Snowflake client as the `ripdpi-snowflake` helper for Tor bridges.

No `native/rust/crates/ripdpi-snowflake` crate should be created under the current Rust WebRTC stack. `PluggableTransportManager`, relay-core, `RelayKind`, `RelayBackend`, `RelayNativeConfig`, and the Gradle pluggable-transport manifest remain on the Go-backed integration.

## Context

RIPDPI supports Snowflake as an external Tor pluggable transport named `ripdpi-snowflake`. `PluggableTransportManager.torManagedTransports` resolves the packaged helper and supplies its command arguments to Tor. Tor owns managed-PT negotiation and sends bridge parameters through the SOCKS per-connection argument channel. The standalone Snowflake relay rejects startup because a Tor PT does not provide a general application proxy. Tor with Snowflake is limited to proxy mode while VPN socket protection is unsupported.

The rejected native replacement would have created a new Rust crate with a library Snowflake client and a thin PT managed-proxy binary. The intended scope was Snowflake client only: HTTP broker rendezvous with domain fronting, SDP offer/answer exchange, WebRTC DataChannel over webrtc-rs, ICE/STUN, turbotunnel reliability via KCP and smux, multi-proxy collection through `-max`, and Tor PT managed-proxy IPC.

The Go Snowflake client remains the behavioral spec. Reference Rust implementations are cross-check material only and must not override the Go client or be copied into RIPDPI.

- Canonical Snowflake Go client: `gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake`, especially `client/`, `client/lib/`, `common/messages/`, `common/encapsulation/`, and `common/turbotunnel/`.
- Canonical PT IPC: Tor `pt-spec`, especially configuration environment variables, stdout IPC messages, and per-connection SOCKS arguments.
- Reference only: Arti `arti-client/examples/snowflake.rs` shows how Arti configures an external Snowflake PT, including bridge-line parameters and `snowflake-client` process configuration.
- Reference only: webrtc-rs/rtc is the WebRTC stack candidate for the Rust port.

## Rationale

The pinned Go Snowflake client uses Pion WebRTC plus `covert-dtls`. It parses `covertdtls-config` and `covertdtls-fingerprint`, then installs `SetDTLSClientHelloMessageHook` on Pion's `SettingEngine` when the selected mode requires it. RIPDPI sets the Tor-managed process default with `-covertdtls-config mimic`; upstream otherwise defaults to `disable`. An explicit bridge `covertdtls-config` or `covertdtls-fingerprint` retains upstream precedence. Broker TLS imitation is a separate setting: users can specify `utls-imitate` in their bridge. RIPDPI does not add or replace it.

The inspected webrtc-rs stack does not expose an equivalent WebRTC-level ClientHello rewrite hook. Its DTLS client hello is generated from fixed DTLS structs: version, random, cookie, cipher-suite list, compression methods, and extensions assembled by `rtc-dtls` flight generation. It supports configurable DTLS role, SRTP profiles, replay windows, and related knobs, but that is not the same as mimicking or randomizing the raw DTLS ClientHello shape.

A replacement must preserve the configured DTLS ClientHello behavior as well as protocol function. These source-level capabilities do not prove resistance to blocking on a particular network. Network-specific compatibility needs separate acceptance evidence.

## Consequences

The supported path is profile bridge configuration → Tor runtime configuration → managed Snowflake helper → Snowflake proxy → Tor bridge. Broker, fronting, ICE/STUN, DTLS and DataChannel behavior belongs to the pinned upstream client, not to the manifest or the inactive standalone launch builder.

- Output name: `ripdpi-snowflake`; packaged executable: `libripdpi-snowflake.upstream.so`.
- Source: official `snowflake.git`, commit `3bd77dc0f574255d072f70a840e18c0af3d43866` (v2.15.3), package `./client`, Go `go1.26.10`.
- Managed method: `snowflake`; Tor supplies the PT environment and owns helper shutdown.
- Process arguments: `-covertdtls-config mimic`.
- Per-connection parameters: the user's bridge options, including rendezvous, ICE, optional `utls-imitate`, and optional DTLS overrides. Bridge strings are not rewritten.
- `ripdpi-obfs4` remains on the independent Lyrebird source pin. It receives no Snowflake process arguments.

`native/pluggable-transports/sources.json` is the build source of truth. The prior Lyrebird pin bundled a Snowflake method, but did not implement the advertised CovertDTLS options. Reusing its output name did not provide those options. The official Snowflake source now owns this method. A source manifest establishes build intent; source build receipts, ELF checks, and runtime tests establish separate evidence. A local DTLS capture or managed-PT handshake does not prove Android APK execution or external broker/proxy/Tor interoperability.

### Android build compatibility

The Snowflake source entry alone adds `-checklinkname=0` to its Go linker arguments. Its pinned `github.com/wlynxg/anet` v0.0.5 requires this option with Go 1.23 or later to update Android IPv6 zone caches; see the [upstream build requirement](https://github.com/wlynxg/anet/blob/839bc3a920f1b87dd3ce1386e425aa5ef2e69d24/README.md#how-to-build-with-go-1230-or-later). This is a private Go API compatibility exception. It does not apply to Lyrebird or cloudflared. The source receipt records these flags, and prebuilt import rejects a missing or different value for Snowflake.

The cache layout was checked at this pin: `anet/interface_android.go:157` matches Go 1.26.10 `net/interface.go:182` and `golang.org/x/net` v0.57.0 `internal/socket/sys_posix.go:112`: `sync.RWMutex`, `time.Time`, `map[string]int`, `map[int]string`, in that order. These private layouts are not stable APIs. Recheck them and build all four Android ABIs whenever the Snowflake source or Go toolchain changes. Direct ELF builds establish compile/link evidence; Android interface enumeration and IPv6 zone use still require device acceptance.

There is no Rust slice plan or `ripdpi-snowflake` crate under this decision.

## Revisit Trigger

Revisit only if the Rust WebRTC stack gains a DTLS ClientHello mimicry or shaping hook equivalent to Pion DTLS v3 plus `covert-dtls`, or if `covert-dtls` is ported to Rust.
