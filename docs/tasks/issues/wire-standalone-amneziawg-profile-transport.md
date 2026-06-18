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
updated: 2026-06-18
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
- [~] AmneziaWG profile persistence + selection. **Persistence: done** — a
      dedicated AWG profile store (Room, stable opaque `awg-<UUID>` id reused as
      the runtime `profileId`) landed, taking the recommended low-blast-radius
      path over a proto bump (see Work log 2026-06-18). **Selection: open** — see
      the "Settings-gating design decision (D2)" section: gating activation
      through `app_settings.proto` + `SharedProxyRuntimeStack` is rejected
      (no-go-needs-design); the additive-proto shape, if a decorative flag is
      later wanted, is recorded there.
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

## Settings-gating design decision (D2 — settings enable seam)

**Decision: do NOT gate standalone AmneziaWG activation through
`app_settings.proto` + `SharedProxyRuntimeStack` (no-go-needs-design).**
The additive proto field is clean in isolation, but folding standalone-AWG
*activation* into the settings-driven proxy stack is architecturally wrong and
would introduce a racing second lifecycle. The persistence half of AC line 79
is now closed by a dedicated AWG profile store (the recommended low-blast-radius
path — see Work log 2026-06-18); the *selection + composition* half (this
decision) is what remains open.

### Why the proxy-stack gate is rejected (verified against source 2026-06-18)

1. **Composition mismatch.** `SharedProxyRuntimeStack.start()` composes only the
   upstream proxy legs (`relayConfigOrNull()`, `warpConfigOrNull()`) into a
   single `LocalProxyEndpoint` fed to `Tun2Socks` via
   `VpnRuntimeCompositionCoordinator`. Standalone AWG is a *full WireGuard
   tunnel* whose `AmneziaWgRuntimeSupervisor` produces no `LocalProxyEndpoint`,
   so it cannot slot into that composition. (`SharedProxyRuntimeStack.kt` has
   zero AWG references.)
2. **No data path for `amneziaWgConfigOrNull()`.** The warp/relay seam reads from
   decoded `RipDpiProxyUIPreferences` and serializes to the native proxy JSON via
   `toNativeConfigJson()` (pinned by `NativeConfigContractSnapshotTest`). The AWG
   path consumes `ResolvedRipDpiAmneziaWgConfig` directly via
   `RipDpiAmneziaWgRuntime.start()`, bypassing UI-preferences and the contract
   JSON entirely — there is no source to read from nor sink to write to. (No
   `amneziaWgConfigOrNull` symbol exists anywhere in the repo.)
3. **Duplicate / racing entry point.** `StandaloneAmneziaWgActivator` already
   owns serialized `activate`/`deactivate` behind a `lifecycleLock` Mutex driving
   `AmneziaWgRuntimeSupervisor`. A settings-gated start inside the proxy stack
   would be a second uncoordinated entry point to the same native AWG runtime —
   exactly the "duplicate the activator path" hazard.

### The additive-proto shape, if a settings-visible flag is later wanted

If a settings-surfaced "AWG enabled" *intent* state is desired, the maximal
additive slice is **decorative only** and must NOT fold activation into the proxy
stack:

- one additive proto3 bool (e.g. `bool standalone_awg_enabled = <next-free>;` —
  highest field in use is 409, no monotonic schema-version counter exists in
  `AppSettingsSerializer`, so this is pure wire-compatible proto evolution: no
  native schema bump, no migration);
- a `WarpSettings`-style model projection (`toAwgSettingsModel().enabled`),
  default `false` (mirroring `warp_enabled` / `relay_enabled` defaults);
- activation **still** driven by `StandaloneAmneziaWgActivator` + the persisted
  profile store — the flag records UI intent, it does not start the runtime.

This is decorative until the composition fork below is decided; shipping the bool
without that decision adds a setting that nothing meaningfully gates.

### Remaining blocker (the actual design work)

Whether standalone AWG **joins VPN composition** (becomes the active egress fed
to `Tun2Socks`, requiring a `LocalProxyEndpoint`-equivalent or a parallel TUN
attach path) **or stays an independent out-of-band tunnel** (current activator
behaviour) is an undecided architectural question. Until that fork is resolved,
"a persisted profile is the active tunnel across restarts" cannot be wired
correctly — the persisted-profile prerequisite is met, but the
selection-into-composition seam is not. AC lines 87-90 (service wiring) remain
open and gate this.

## Work log

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
- 2026-06-18: Settings-gating (D2) classified **no-go-needs-design** after a
  source audit (`SharedProxyRuntimeStack.kt` has no AWG arm; no
  `amneziaWgConfigOrNull` symbol exists; `StandaloneAmneziaWgActivator` owns a
  `lifecycleLock` Mutex). No proto field added, no composition path duplicated.
  The decision, the rejected proxy-stack gate (3 verified reasons), the additive
  decorative-only proto shape if a flag is later wanted, and the remaining
  composition-fork blocker are recorded in the "Settings-gating design decision
  (D2)" section above. Persisted-profile prerequisite is now met; the
  selection-into-composition seam (AC service-wiring lines) is the gating work.

## References

- Sibling cohort task: `wire-amneziawg-rtk-south-jc4-cohort-into-android-client`
  (RTK-South Jc=4 parameters + probabilistic retry — the data this transport
  consumes).
- `docs/architecture/NATIVE_RUST.md` §1/§2/§6 (the new L8 crate).
- `.claude/rules/vpnservice-protect-invariant.md` (outbound UDP socket protect).
- Different mechanism: `add-wireguard-over-websocket-transport-amneziawg-disguise`.
