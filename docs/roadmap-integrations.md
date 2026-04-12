# Integrations Roadmap

This document now tracks open integration work only.

The earlier phase split (`P0` through `P3`) was useful while the transport and control-plane program was landing, but it is now removed. Current shipped behavior is summarized here and in the companion native/runtime notes.

## Current Platform Baseline

RIPDPI already ships the following integration stack in-repo:

- WARP with native runtime support, endpoint selection, and AmneziaWG-compatible obfuscation controls.
- Relay transports for VLESS Reality over xHTTP, Cloudflare Tunnel, MASQUE, Hysteria2, TUIC v5, ShadowTLS v3, and NaiveProxy.
- Cloudflare Tunnel in both `consume_existing` and `publish_local_origin` modes, with import-backed credential storage and Android-managed publish helpers.
- Cloudflare-direct MASQUE with mTLS client identity, optional `sec-ch-geohash`, Privacy Pass provider wiring, and HTTP/3 to HTTP/2 fallback.
- Finalmask on the xHTTP transport family for supported relay kinds and modes, with runtime validation for unsupported combinations.
- Signed strategy-pack delivery, rollout flags, bundled fallback catalogs, and versioned TLS profile catalogs.
- Relay credential storage through `RelayCredentialStore`, keeping secrets out of protobuf settings.
- Automatic probing, automatic audit, remembered per-network policies, and relay/runtime telemetry that feed rollout and validation work.

## Current Documentation Set

Use these notes as the source of truth for shipped behavior:

- [Native integration and modules](native/README.md)
- [Cloudflare Tunnel operations](native/cloudflare-tunnel-operations.md)
- [MASQUE current state](native/relay-masque-status.md)
- [NaiveProxy runtime](native/relay-naiveproxy-decision.md)
- [Finalmask compatibility](native/finalmask-compatibility.md)
- [Strategy-pack and TLS catalog operations](strategy-pack-operations.md)
- [Relay profile examples](relay-profile-examples.md)
- [Testing and verification](testing.md)

## Active Roadmap

The roadmap is now limited to ongoing validation, transport expansion, and operational refresh work.

| Track | Goal | Current State | Exit Signal |
| --- | --- | --- | --- |
| Cloudflare-direct MASQUE rollout | Broaden provider and network validation for `cloudflare_mtls` | Core transport, auth, validation, and telemetry are implemented | Stable field validation across representative networks and endpoints without transport-specific overrides |
| Cloudflare Tunnel publish rollout | Harden publish-mode operations and supportability | Bundled `cloudflared` + local xHTTP origin helper are implemented | Operational docs, crash telemetry confidence, and stable publish behavior across supported Android targets |
| Finalmask expansion | Extend Finalmask beyond current xHTTP coverage | `header-custom`, `fragment`, and `Sudoku` work on supported xHTTP transports | Upstream-parity tests and live transport support for any newly added mode or transport family |
| NaiveProxy operational confidence | Continue field validation of subprocess helper behavior | Version probing, readiness handshake, error classification, redaction, and bounded restarts are implemented | Low-noise watchdog behavior and validated upstream compatibility across common auth and TLS edge cases |
| TLS and strategy-pack freshness | Keep remote control-plane data current | Signed catalogs, compatibility checks, and bundled fallbacks are implemented | Catalog rotation remains routine and does not require code changes for ordinary fingerprint and rollout updates |
| Relay interoperability matrix | Expand cross-transport test confidence | Unit and service coverage exist for relay config, MASQUE, xHTTP, Finalmask, and helper lifecycles | CI coverage closes the remaining provider and transport edge cases without relying on manual hand checks |

## Near-Term Work

### 1. Cloudflare-direct MASQUE rollout validation

Focus:

- wider endpoint sampling for `cloudflare_mtls`
- broader validation of H3 to H2 fallback behavior
- confirmation that runtime telemetry is sufficient for staged rollout decisions

This is no longer a protocol-construction project. The remaining work is rollout confidence.

### 2. Cloudflare Tunnel publish-mode hardening

Focus:

- publish-mode failure reporting and operator-facing diagnostics
- lifecycle validation for `cloudflared` and `ripdpi-cloudflare-origin`
- support guidance for imported credentials, hostname mapping, and origin health checks

Cloudflare onboarding remains import-based. The app still does not provision Cloudflare resources through account APIs.

### 3. Finalmask transport expansion

Focus:

- parity-driven implementation of additional modes such as `noise`
- any future QUIC-side support only after upstream-compatible vectors exist
- continued validation that unsupported combinations fail fast in both Kotlin and Rust

### 4. NaiveProxy field validation

Focus:

- additional compatibility coverage for upstream auth combinations
- watchdog tuning based on real helper failure patterns
- maintaining clear helper boundaries instead of growing toward a browser-engine embedding model

### 5. Strategy-pack and TLS catalog operations

Focus:

- refresh cadence for TLS fingerprints and rollout defaults
- compatibility and rollback hygiene for new strategy-pack revisions
- keeping feature flags as the only remote control surface for transport rollouts

## Guardrails

- New transport capability should land behind strategy-pack rollout flags unless it is purely local tooling.
- Secrets stay in `RelayCredentialStore` or helper-managed secure material, not in protobuf settings.
- Native transport features must fail closed when a mode or transport is not actually implemented.
- New remote behavior should prefer catalog updates and rollout metadata over hardcoded app defaults.

## Not Planned

- Cloudflare account API provisioning from inside the app.
- Browser-engine or WebView embedding for NaiveProxy.
- QUIC-side Finalmask rollout without upstream-compatible parity tests.

## Historical Note

The old phase implementation files were removed because they had become an archive of already-shipped work and were making the roadmap harder to use. Future planning should update this document and the focused runtime notes instead of recreating per-phase implementation files.
