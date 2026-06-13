---
title: "Make the AmneziaWG profile UI establish a real tunnel (standalone AWG transport)"
type: task
status: doing
area: transport
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-06-13
updated: 2026-06-13
source_wiki_pages:
  - "wireguard-rtk-south-amneziawg-bypass"
linked_task: "wire-amneziawg-rtk-south-jc4-cohort-into-android-client"
---

## Motivation

The `AmneziaWgProfileScreen` / `AwgProfileForm` editor lets a user configure a
full AmneziaWG peer (endpoint, keys, MTU, DNS, and the Jc/Jmin/Jmax/S1-S2/H1-H4/
I1-I5 obfuscation knobs) — but **the app could not run it**. The editor was
preview-only: no Save/Connect, no persistence, no engine path. This is the same
"UI-complete, core-stub" gap as SSH (G1). Distinct from WARP, which only drives
Cloudflare's WireGuard endpoints.

## Design & dependency decision

AmneziaWG = WireGuard (Noise_IKpsk2) + an additive obfuscation layer. The
WireGuard + AmneziaWG **data plane already exists and is tested** inside
`ripdpi-warp-core` (boringtun 0.7.1 + the `amneziawg.rs` codec + the smoltcp
userspace netstack + a loopback SOCKS5 front end), driven today by `WarpRuntime`
for Cloudflare WARP.

**Decision: reuse `ripdpi-warp-core` rather than fork a new Noise crate.**
boringtun and the AWG codec already live there and are vetted by `cargo deny`;
forking a second Noise implementation would duplicate the handshake and double
the unsafe/audit surface. The task spec sketched a `ripdpi-amneziawg-core` fork
of boringtun — rejected for that reason (and consistent with the prior in-repo
decision recorded in `ripdpi-warp-core/src/amneziawg.rs`). The new code is an
additive *runtime + obfuscation config* layer, not a crypto fork.

Surface:
- **`ripdpi-warp-core`** (L7): new `AmneziaWgRuntime` + `AmneziaWgProfileConfig`
  (a non-Cloudflare config). `WireGuardTunnel` generalized via
  `WireGuardTunnelParams` to add the three deltas a generic peer needs over
  WARP — an optional preshared key, a configurable persistent-keepalive
  interval, and the AWG 2.0 I1-I5 special-junk frames. WARP/probe call sites
  pass WARP's existing behaviour verbatim (byte-identical on the wire).
- **`ripdpi-amneziawg-android`** (L8, new cdylib → `libripdpi-amneziawg.so`):
  the `RipDpiAmneziaWgNativeBindings` JNI bridge, mirroring `ripdpi-warp-android`
  minus the Cloudflare provisioning / endpoint-probe entries. The outbound UDP
  socket is kept off the TUN via the shared `ripdpi-native-protect` callback.
- **Integration model = WARP-style tunnel, NOT a `ProxyProfile` relay.** The
  runtime presents a loopback SOCKS proxy; the Android `VpnService` TUN→SOCKS
  bridge (`Tun2Socks`) routes through it exactly as it does for WARP. AWG is
  therefore NOT added to the `ProxyProfile` sealed interface.

## Acceptance criteria

- [x] Native generic AmneziaWG runtime (`AmneziaWgRuntime`) reusing the
      boringtun + AWG-codec + netstack + SOCKS data plane; PSK + keepalive +
      I1-I5 supported. (`ripdpi-warp-core`)
- [x] Data-plane proof: a real two-peer Noise_IKpsk2 handshake completes with
      every wire packet passed through the **active** AmneziaWG codec, and an
      inner IPv4 packet survives the round trip
      (`obfuscated_handshake_completes_and_transports_a_packet`).
- [x] JNI cdylib bridge `ripdpi-amneziawg-android` (`RipDpiAmneziaWgNativeBindings`),
      panic-contained, protect-registered; built per ABI via
      `rustNativeArtifactSpecs`; registered as the 13th L8 crate in the native
      architecture contracts + `NATIVE_RUST.md`.
- [x] Kotlin binding contract layer: `ResolvedRipDpiAmneziaWgConfig` DTO +
      `RipDpiAmneziaWgRuntime` interface + `RipDpiAmneziaWgNativeBindings`
      (JNI loader + external funs) + `RipDpiAmneziaWg` runtime wrapper +
      Hilt factory/binding module, with `RipDpiAmneziaWgConfigSerializationTest`
      guarding the cross-language JSON field names (top-level + nested `amnezia`
      keys, h1-h4 numeric Long, round-trip). `:core:engine`/`:core:engine-api`
      compile + the test pass.
- [ ] AmneziaWG profile persistence + selection: a store for AWG profiles and a
      way to mark one "active" for a connection. **Open design point** — the
      connection flow is settings/Mode-driven (`ConnectionPolicyResolver` reads
      `AppSettings`), so selecting an AWG profile as the VPN egress requires
      either new `app_settings.proto` fields (wire-schema change — high-risk
      shared surface) or a dedicated AWG profile store mirrored from the WARP
      `WarpProfileStore`/`WarpCredentialStore` pattern. Recommend the latter to
      avoid a proto bump, plus a single `selectedAmneziaWgProfileId` setting.
- [ ] Service wiring: `AmneziaWgRuntimeSupervisor` + composition coordinator
      integration so the runtime's loopback SOCKS endpoint becomes the
      `LocalProxyEndpoint` handed to `Tun2Socks` (mirror `WarpRuntimeSupervisor`
      + `SharedProxyRuntimeStack` + `VpnRuntimeCompositionCoordinator`).
- [ ] UI connect path: `AmneziaWgProfileViewModel.onSave()/onConnect()` →
      persist + start the tunnel. Localize new user-facing strings in all 8
      locales.
- [ ] On-device / loopback-fixture interop smoke test against a real AmneziaWG
      server (see the linked RTK-South cohort task for parameters); probabilistic
      retry tuning lives there.

## Verification status (this PR)

- Native: `cargo test -p ripdpi-warp-core -p ripdpi-amneziawg-android` green
  (incl. the obfuscated-handshake proof); `cargo clippy -D warnings` + `cargo fmt`
  clean; `Cargo.lock` adds only the new local member; native architecture
  contracts pass (0 violations).
- The end-to-end "device traffic egresses through the AWG tunnel" path depends
  on the remaining Kotlin service/UI/selection wiring above and a real server
  for interop; those are explicitly NOT claimed verified here.

## References

- Sibling cohort task: `wire-amneziawg-rtk-south-jc4-cohort-into-android-client`
  (RTK-South Jc=4 parameters + probabilistic retry — the data this transport
  consumes).
- `docs/architecture/NATIVE_RUST.md` §1/§2/§6 (the new L8 crate).
- `.claude/rules/vpnservice-protect-invariant.md` (outbound UDP socket protect).
- Different mechanism: `add-wireguard-over-websocket-transport-amneziawg-disguise`.
