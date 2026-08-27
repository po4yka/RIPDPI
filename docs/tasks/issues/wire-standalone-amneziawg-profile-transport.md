---
id: TRN-1786264762917775
title: Make the AmneziaWG profile UI establish a real tunnel (standalone AWG transport)
kind: feature
status: review
area: transport
priority: high
owner: unassigned
parent: null
blocked_by: []
spec_mode: required
openspec_change: trn-1786264762917775-wire-standalone-amneziawg-profile-transport
created: 2026-06-13
updated: 2026-08-27
source_wiki_pages:
  - wireguard-rtk-south-amneziawg-bypass
linked_task: TRN-1786264762917677
status_detail: Implementation and independent loopback interop passed; final static analysis and exact-SHA hosted CI tracked in verification. Existing baseline guard failures prevent closure.
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
- [x] AmneziaWG profile persistence + selection. A dedicated AWG profile store
      (Room, stable opaque `awg-<UUID>` id reused as the runtime `profileId`)
      persists profiles, and the selected `AwgActivationRequest` now flows into
      the shared VPN/proxy runtime stack without a proto bump.
- [x] Service wiring: `AmneziaWgRuntimeSupervisor` + composition coordinator
      integration so the runtime's loopback SOCKS endpoint becomes the
      `LocalProxyEndpoint` handed to `Tun2Socks` (mirror `WarpRuntimeSupervisor`
      + `SharedProxyRuntimeStack` + `VpnRuntimeCompositionCoordinator`).
- [x] UI connect path: `AmneziaWgProfileViewModel.onSave()/onConnect()` →
      persist + start the tunnel. Localize new user-facing strings in all supported
      locales.
- [x] On-device / loopback-fixture interop smoke test against a real AmneziaWG
      server (see the linked RTK-South cohort task for parameters); probabilistic
      retry tuning lives there.

## Verification status

- The editor obtains VPN consent and waits for exact runtime application. The
  service supports cold start, TUN-preserving replacement, profile DNS/routes/MTU,
  and dual-stack configuration without changing the native DTO.
- Real bidirectional IPv4/IPv6 TCP and UDP passed against the pinned independent
  rootless `amneziawg-go` peer. This is loopback protocol evidence, not an Android
  device or VPS claim. The existing network E2E CI script now runs this fixture.
- The final combined Kotlin run passed 3059 tests, including the complete service
  suite (1884); native unit tests (93) and network E2E tests (62) also passed.
  Static analysis and exact-commit hosted CI are tracked in the linked
  OpenSpec verification record. Existing failures on baseline main are not
  treated as successful acceptance; the item must remain open until resolved.

## Runtime-composition decision (D2 — resolved)

**Current decision:** standalone AWG joins VPN composition as an owned egress
selection, not as a relay profile and not as a second uncoordinated lifecycle.
The selected `AwgActivationRequest` remains outside `app_settings.proto`; it is
session/runtime state supplied by `AwgEgressSelectionProvider` from the profile
store or simple-flavor failover selector. `SharedProxyRuntimeStack` starts the
owned `AmneziaWgRuntimeSupervisor`, receives the AWG loopback SOCKS endpoint,
and rewrites the proxy upstream to that endpoint before `Tun2Socks` starts.

The 2026-06-18 "no-go-needs-design" note is superseded by the 2026-06-21
implementation series. Its core concerns were addressed as follows:

1. **Composition mismatch resolved.** `SharedProxyRuntimeStack` now has an AWG
   arm and treats AWG as the top-precedence local egress endpoint. The relay/WARP
   proxy legs no longer silently override AWG.
2. **Data path resolved without a proto bump.** `RipDpiProxyPreferences.awgConfigOrNull()`
   carries the live AWG selection through proxy wrappers, remembered-policy replay,
   and simple-failover flows. `ResolvedRipDpiAmneziaWgConfig` remains the native
   runtime DTO; native proxy JSON is only rewritten to point traffic at the local
   AWG SOCKS endpoint.
3. **Lifecycle ownership resolved.** `VpnServiceRuntimeCoordinator` owns the
   `AmneziaWgRuntimeSupervisor` and passes it into shared-stack start/stop,
   telemetry, and exit handling. Standalone UI activation and simple failover
   share the same provider-backed selection instead of binding separate in-memory
   providers.

## Work log

### 2026-08-27 implementation ownership

- Coordinator: `/private/tmp/ripdpi-standalone-awg-profile-20260827`; owns
  service composition, serialized contracts, task/OpenSpec records, integration, and final gates.
- Kotlin editor writer: `/private/tmp/ripdpi-standalone-awg-kotlin-tests-20260827`;
  owns editor state/consent/activation, activation DTO validation, Simple fallback,
  and the shared explicit-start generation guard across service dispatch/preparation.
- Native interop writer: `/private/tmp/ripdpi-standalone-awg-interop-20260827`;
  owns AWG runtime/netstack fixes, independent rootless peer tooling, and native regressions.
- Contract/schema, dependency-lock, locale, and golden changes remain serialized
  with the coordinator. Writers must not edit those files without reassignment.
- Positive acceptance requires the editor activation path and real bidirectional
  TCP/UDP through the production runtime to an independently implemented local
  AWG peer. Loopback evidence does not establish physical-device or real-VPS behavior.

- 2026-06-18: Persistence half of AC "profile persistence + selection" closed.
  A dedicated AWG profile store (Room entity + DAO + repository, stable opaque
  `awg-<UUID>` primary key) now persists the editor's `AwgActivationRequest`
  blob and re-uses the row id as `AwgActivationRequest.profileId` on every
  Connect (`AmneziaWgProfileViewModel.onConnect()`), closing the
  per-activation fresh-UUID deferral. Activation still flows through the
  out-of-band `StandaloneAmneziaWgActivator` — NOT the proxy stack. The opaque
  id satisfies the privacy invariant (never endpoint-derived); the endpoint
  host/port live only inside the persisted user-config blob and must not reach
  telemetry/logs.
- 2026-06-18: Settings-gating (D2) was blocked pending a composition decision, so no proto field was added and no duplicate lifecycle path was introduced. That temporary design state is superseded by the resolved runtime-composition decision above; the durable part of the finding is that AWG selection remains runtime/profile state, not `app_settings.proto` state.
- 2026-06-21: Source refresh. The service/composition blocker recorded above is now closed in `main`: `SharedProxyRuntimeStack` accepts `awgConfigOrNull()`, starts `AmneziaWgRuntimeSupervisor`, and rewrites the proxy upstream to `VpnModeAmneziaWgLocalSocksPort`; `VpnServiceRuntimeCoordinator` owns the supervisor and passes it to telemetry and exit handling. The editor path is also closed: `AmneziaWgProfileViewModel.onConnect()` persists through `AwgProfileRepository`, reuses the opaque stable `awg-<UUID>` id, and activates through `StandaloneAmneziaWgActivator`. Remaining open work is external interop: a real/synthetic AmneziaWG endpoint smoke and any probabilistic retry tuning linked to the RTK-South cohort task.

## References

- Sibling cohort task: `wire-amneziawg-rtk-south-jc4-cohort-into-android-client`
  (RTK-South Jc=4 parameters + probabilistic retry — the data this transport
  consumes).
- `docs/architecture/NATIVE_RUST.md` §1/§2/§6 (the new L8 crate).
- `.claude/rules/vpnservice-protect-invariant.md` (outbound UDP socket protect).
- Different mechanism: completed task `add-wireguard-over-websocket-transport-amneziawg-disguise` (see git history).
