---
id: TRN-1786264762917677
title: Verify AmneziaWG interoperability and tune RTK-South retries
kind: feature
status: blocked
area: transport
priority: medium
owner: AmneziaWG interoperability maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: trn-1786264762917677-wire-amneziawg-rtk-south-jc4-cohort-into-android-client
created: 2026-05-22
updated: 2026-08-09
source_wiki_pages:
  - wireguard-rtk-south-amneziawg-middlebox-compatibility
status_detail: Standalone runtime, UI, import, and composition are implemented; external AWG endpoint evidence and retry-budget measurements are unavailable.
---

## Motivation

Plain WireGuard on the observed regional network path experiences periodic 20–30 second interruptions every ~30 seconds — middlebox/device fingerprinting can identify WireGuard via the deterministic 148-byte Initiation packet structure (4-byte type, 4-byte sender index, 32-byte ephemeral public key, 48-byte encrypted static key, 28-byte encrypted timestamp, 16-byte MAC1, 16-byte MAC2). AmneziaWG (AWG) randomizes this signature with junk/header/initialization parameters.

Community-tested working parameters for the observed cohort: `Jc=4 Jmin=10 Jmax=50 S1-4=0 H1=1 H2=2 H3=3 H4=4` plus per-deployment `I1-I5`. Connects successfully though sometimes requires 3–4 attempts (probabilistic passing — the middlebox rule may be threshold-based rather than a hard block).

> [!info] Dedup notes
> The workspace now contains the AmneziaWG Android/native implementation. The completed WG-over-WebSocket transport-variant task (see git history) covered a different mechanism.

## Proposed change

1. Add AmneziaWG client support to RIPDPI Android. If the existing WireGuard implementation lives in the Kotlin layer, extend it with AWG parameter fields (Jc/Jmin/Jmax/S/H/I); if it lives in a Rust crate or via sing-box wrapping, create or extend a corresponding `ripdpi-amneziawg` crate.
2. Per-cohort profile selector in proxy config — when the user connects via a profile tagged for RTK South (or similar AmneziaWG-required network), apply cohort-specific parameters from the deploy-side cohort YAML.
3. JNI/Kotlin diagnostic surface for AWG vs plain WG mode selection.
4. Probabilistic-retry logic: if AWG handshake fails, retry up to 4 attempts (per community report of 3–4 attempts to succeed).

### Deploy evidence

The server-side cohort was delivered by immutable deploy commit
[`aa6d5d228866cbc27d89538aa51ba8250dfcee11`](https://github.com/po4yka/ripdpi-vpn-deploy/commit/aa6d5d228866cbc27d89538aa51ba8250dfcee11).
It is completed evidence, not an active cross-repository backlog dependency.

## Acceptance criteria

- [x] AmneziaWG client support compiles for all 4 Android ABIs.
- [x] Cohort profile import populates Jc/Jmin/Jmax/S/H/I from server-provided YAML or subscription URL.
- [ ] Interoperability smoke against an external AWG endpoint with RTK-South parameters succeeds on the exact Android artifact.
- [ ] Retry budget is derived from observed handshake evidence, bounded, configurable per cohort, and regression-tested.
- [x] Dedup confirmed: distinct from the completed WG-over-WebSocket transport-variant task — this task wires AmneziaWG packet-shape randomization (Jc/Jmin/Jmax/H/S/I) into the existing `ripdpi-warp-core` WG kernel; the other adds a WG-over-WebSocket tunnel variant. Different layers.

## Risks / open questions

- Whether RIPDPI already has Kotlin-layer WireGuard integration (sing-box ships WG; if RIPDPI wraps that, AWG support may already be partially available via sing-box config) — verify before writing a new crate.
- "Sometimes stalls on handshake requiring 3–4 connection attempts" — probabilistic; empirical retry-budget tuning per ISP may be needed.
- Jc=4 was measured at one RTK South vantage; other Rostov-region Rostelecom nodes may have different thresholds.

## Work log

- 2026-06-05: AWG Rust kernel complete — `ripdpi-warp-core/src/amneziawg.rs` implements full Jc/Jmin/Jmax/H1-H4/S1-S4/I1-I5 codec with unit tests; `wireguard/tunnel.rs` wires it into the tunnel; proto fields `warp_amnezia_*` exist in `app_settings.proto`; `WarpAmneziaConfig` struct in config.rs carries all parameters. Remaining work: cohort profile import from server YAML (no Kotlin mapping found), probabilistic-retry logic (not implemented), JNI/Kotlin diagnostic surface for AWG mode (no Kotlin amnezia references found), smoke test against synthetic AWG endpoint.
- 2026-06-05 (audit): Criterion 1 [x] confirmed — `ripdpi-warp-android` (in workspace targeting all 4 Android ABIs per `rust-toolchain.toml`) depends on `ripdpi-warp-core` which contains the AWG codec; `ResolvedRipDpiWarpConfig.amnezia` passes the config to the native layer via JNI. Criterion 2 upgraded to [~] (partial): bundled asset `core/data/runtime-state/src/main/assets/awg-cohorts.json` ships the `rtk_south` preset with Jc=4/Jmin=10/Jmax=50/H1=1..H4=4 and `applyCohortPreset()` / `matchCohortForConf()` are implemented in `AwgCohortCatalog.kt`; however server-side YAML or subscription URL fetch is explicitly deferred ("out of scope") per `AwgCohortCatalog.kt` KDoc. Criteria 3 (smoke test), 4 (retry logic), and 5 (dedup PR note) remain unimplemented. Status changed from `todo` to `doing`.
- 2026-06-21: Source refresh. Cohort/subscription import is now closed on the client side: `WireguardIniSubscriptionParser` emits `AmneziaWgSubscriptionProfile`, simple-flavor seeding maps it to `AwgActivationRequest`, and `AwgProfileRepository` persists the resulting profile with an opaque stable id while moving private/preshared keys to `AwgCredentialStore`. `AmneziaWgProfileViewModel` can persist and activate the request, and the service layer maps it into `ResolvedRipDpiAmneziaWgConfig`. Remaining work is not client import plumbing: it is the synthetic/real endpoint smoke with RTK-South parameters plus probabilistic retry-budget tuning after that lab evidence exists.

## References

- wireguard-rtk-south-amneziawg-middlebox-compatibility — internal concept page with cohort parameters
- Deploy receipt: `po4yka/ripdpi-vpn-deploy@aa6d5d228866cbc27d89538aa51ba8250dfcee11`
- Related (different mechanism): completed WG-over-WebSocket transport-variant task (see git history)
