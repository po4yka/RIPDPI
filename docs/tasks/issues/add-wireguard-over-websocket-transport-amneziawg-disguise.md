---
title: Add WireGuard-over-WebSocket transport with AmneziaWG disguise
type: task
status: doing
area: rust-native
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-16
updated: 2026-06-21
---

## Summary

Encapsulate WireGuard handshakes and data frames inside a WebSocket stream (WSS over 443). Combined with AmneziaWG's junk-packet prefix and randomized header values, this eliminates both the distinctive WG UDP fingerprint (handshake initiation MTU/checksum, message-type byte) and the well-known WG UDP port range.

## Context

WireGuard's UDP fingerprint is one of the easiest DPI signatures in the wild: a fixed 148-byte handshake initiation with type byte 0x01. Russian TSPU drops these inline. Wrapping the frames inside WSS makes them look like ordinary HTTP/1.1 Upgrade traffic to port 443. AmneziaWG's `Jc`/`Jmin`/`Jmax` junk parameters defeat the residual "first packet shape" heuristic by inserting random padding before the real handshake.

`docs/amneziawg-uri-scheme.md` already describes the configuration schema. This task adds the WS transport carrier.

## Acceptance criteria

- [~] New crate `ripdpi-wireguard-ws` implementing the WireGuard-over-WSS transport adapter (encrypt/decrypt frames, drive WS framing).
- [~] AmneziaWG junk-packet generation (Jc/Jmin/Jmax) is wired into the pre-handshake stream.
- [ ] Configuration via existing `core:data:model` typed schema (extend `WireguardOutbound`).
- [~] Loopback test exercises a complete WG handshake through a WSS pair without any real internet.
- [x] Telemetry: counter increments on successful WG handshake through the WS carrier.

## Risks / open questions

- WG userspace implementation: use `boringtun` or implement Noise_IK directly. `boringtun` is simpler but pulls in another crate dep with its own pinning concerns.
- Mobile MTU: tunneling WG inside WS inside TLS easily blows through 1500-byte MTU. PMTU discovery (see `add-quic-path-mtu-discovery-regression-test`) is relevant.

## Links

- `docs/amneziawg-uri-scheme.md`
- ws-tunnel-telegram
- AmneziaWG protocol spec

## Work log

- 2026-06-05: No implementation exists; ripdpi-wireguard-ws crate absent, no WireGuard Rust code found anywhere in native/rust/crates/, no AmneziaWG junk-packet or Jc/Jmin/Jmax wiring, no WireguardOutbound schema extension. All acceptance criteria unmet; docs/amneziawg-uri-scheme.md present (schema spec only).
- 2026-06-05: Re-verified. `ripdpi-wireguard-ws` crate still absent. AmneziaWG junk-packet logic (Jc/Jmin/Jmax, H1-H4, S1-S4, I1-I5, `handshake_prelude`) IS fully implemented in `native/rust/crates/ripdpi-warp-core/src/amneziawg.rs` for the WARP/UDP path — but this task requires a separate WireGuard-over-WSS transport adapter crate, which does not exist. No `WireguardOutbound` schema extension in `core:data:model`, no loopback WG-over-WSS test, no telemetry counter for WG handshake through WS carrier. Status remains backlog; all criteria remain unmet for this task's specific WS-carrier scope.
- 2026-06-18: Partial. AC1/AC2/AC4-structural landed earlier (carrier crate `ripdpi-wireguard-ws`: `frame_datagram`/`unframe_message` codec, `JunkPrefix` Jc/Jmin/Jmax envelope, `WsCarrier`, loopback round-trip of a synthetic datagram + junk over a `127.0.0.1` WS echo relay). This commit adds the protected-outbound-socket seam: `connect_protected_carrier(target, &protector)` + `CarrierSocketProtector` (mirrors `ripdpi-warp-core::platform::WarpSocketProtector`). It creates an unconnected `tokio::net::TcpSocket`, runs the injected protector on its fd BEFORE `connect()`, and fails closed (drops the socket, propagates the error) on protector rejection — the `VpnService.protect` invariant for the future production carrier socket. Three unit tests cover protect-before-connect (v4 + v6 loopback) and the failing-protector abort.
- 2026-06-18: DEFERRED (pending a runtime consumer) — AC3, AC4-crypto, AC5:
  - AC3 `extend WireguardOutbound`: the `WireguardOutbound` type does not exist anywhere in the repo (zero matches in `.kt`/`.rs`); there is no outbound-transport enum/sealed type a WS carrier slots into, `AwgActivationRequest` carries no transport-carrier field, and the native AmneziaWG/WARP runtime has no WS/TCP carrier seam (it is UDP-only via `bind_tunnel_socket`). Adding a WS-carrier schema/selection now produces a field that nothing reads end-to-end. Deferred until a native runtime carrier path consumes it; adding the schema ahead of a reader is rejected as speculative.
  - AC4-crypto (`complete WG handshake through a WSS pair`): only structurally met. The loopback test round-trips a synthetic 148-byte datagram + junk over plain-TCP WS with no real boringtun Noise handshake and no TLS. A true crypto-end-to-end test needs a `ripdpi-warp-core` (boringtun) dependency edge the carrier crate deliberately avoids for the minimal slice.
  - AC5 (telemetry counter): no production path drives a real WG handshake through the carrier yet, so a counter has nothing to count and cannot be tested end-to-end. Deferred with the carrier consumer.
  - The in-crate protect seam intentionally does NOT register a real protector: the JNI-backed `VpnService.protect` shim lives in a `*-android` adapter outside this crate (cf. `ripdpi-native-protect` / `ripdpi-warp-core::platform`). The seam takes an injected protector so it is concrete and testable today without pulling JNI into the carrier crate.
- 2026-06-21: Source refresh. Runtime consumer work has landed: `AmneziaWgProfileConfig` has additive `carrier` / `carrierWsUrl`, `AwgActivationRequest` and `ResolvedRipDpiAmneziaWgConfig` carry the same fields, `DefaultAmneziaWgRuntimeConfigResolver` validates that WS requests have a non-blank carrier URL, and `AmneziaWgRuntime::open_carrier()` uses `connect_ws_carrier()` plus the injected protect callback. `wireguard::carrier::tests::wg_handshake_completes_through_ws_carrier` now runs a real boringtun handshake through a local WS-to-UDP relay, and the runtime increments `wsCarrierHandshakes` / `wsCarrierHandshakeFailures`. Remaining gaps on `main`: the client path still accepts a generic `request_url` through tungstenite and performs a plain WS upgrade over a protected TCP stream, so real `wss://` endpoint parsing/TLS/SNI semantics are not yet represented in this branch; `JunkPrefix` exists in the carrier crate and has loopback coverage, but `AmneziaWgRuntime` does not currently send that prefix before the first real datagram; there is still no `WireguardOutbound` type to extend.
