# Integrations Roadmap

This document now tracks open integration work only.

The earlier phase split (`P0` through `P3`) was useful while the transport and control-plane program was landing, but it is now removed. Current shipped behavior is summarized here and in the companion native/runtime notes.

Cross-roadmap status (2026-04-17): bypass-modernization Workstream 1 is
complete, bypass-modernization Workstream 2 is complete, bypass-modernization
Workstream 4 is complete, architecture-refactor Workstream 1 is complete,
architecture-refactor Workstream 3 is complete, and architecture-refactor
Workstream 4 is complete. The active work here remains rollout validation,
operational hardening, and catalog freshness.

## Related Roadmaps

This roadmap covers the **transport and control-plane layer** underneath bypass
tactics. It is mostly shipped; remaining work is rollout validation and catalog
freshness. Three sibling roadmaps drive the surfaces above and around it:

- [../ROADMAP.md](../ROADMAP.md) -- master index and cross-roadmap sequencing.
- [../ROADMAP-bypass-modernization.md](../ROADMAP-bypass-modernization.md) --
  strategic bypass roadmap. Its Workstream 5 (TLS shaping, browser-family
  templates, ECH) depends on the TLS fingerprint and strategy-pack rollout
  pipeline tracked here. Browser-family templates ship through the catalog
  system, not hardcoded client defaults.
- [../ROADMAP-bypass-techniques.md](../ROADMAP-bypass-techniques.md) -- tactical
  bypass checklist. Finalmask coverage and NaiveProxy validation here sit beside
  the TCP-level and QUIC-level tactics catalogued there.
- [../ROADMAP-architecture-refactor.md](../ROADMAP-architecture-refactor.md) --
  structural cleanup roadmap. Its Workstream 4 (service and relay orchestration
  decomposition) has now split relay runtime-config resolution into per-relay-kind
  resolvers for MASQUE, Snowflake, Cloudflare Tunnel, ShadowTLS, NaiveProxy,
  chain relay, local-path transports, and default relay families, and has also
  completed the lifecycle/handover/start-stop collaborator split in the service
  coordinators. Future work here should consume that completed seam rather than
  reopening it.

### Ownership Notes

This roadmap owns **integration-layer delivery and rollout**; bypass and refactor
roadmaps own the client tactics and code structure that consume these transports.

| Topic | This roadmap owns | Sibling owner |
|-------|-------------------|---------------|
| TLS fingerprint catalog distribution | strategy-pack rollout + signing | modernization Workstream 5 defines template families |
| Finalmask modes on xHTTP | parity + validation per mode | bypass-techniques catalogues client-side behaviors |
| Cloudflare MASQUE + Tunnel operations | field validation, credential handling | refactor Workstream 4 owns resolver code split |
| NaiveProxy helper lifecycle | watchdog + error classification | refactor Workstream 4 owns resolver interface |
| Relay-kind resolver interface | consumer of refactor Workstream 4 output | refactor Workstream 4 owns the split |

## Current Platform Baseline

RIPDPI already ships the following integration stack in-repo. Crate ownership
captured 2026-04-15:

- **WARP** with native runtime support, endpoint selection, and AmneziaWG-compatible obfuscation controls.
- **Relay transports** for VLESS Reality over xHTTP, Cloudflare Tunnel, MASQUE, Hysteria2, TUIC v5, ShadowTLS v3, and NaiveProxy.
  - xHTTP + Reality + Finalmask: `native/rust/crates/ripdpi-xhttp/`
  - MASQUE: `native/rust/crates/ripdpi-masque/`
  - Cloudflare Tunnel publish-mode origin: `native/rust/crates/ripdpi-cloudflare-origin/`
  - NaiveProxy subprocess helper: `native/rust/crates/ripdpi-naiveproxy/`
  - Relay orchestration: `native/rust/crates/ripdpi-relay-core/`
- **Cloudflare Tunnel** in both `consume_existing` and `publish_local_origin` modes, with import-backed credential storage and Android-managed publish helpers. Kotlin lifecycle in `core/service/.../CloudflarePublishRuntime.kt`; readiness signal `RIPDPI-READY|cloudflare-origin|...`.
- **Cloudflare-direct MASQUE** with mTLS client identity (`ripdpi-masque/src/lib.rs:load_client_identity()`, line ~719), optional `sec-ch-geohash`, Privacy Pass provider wiring (`ripdpi-masque/src/auth.rs:39-72`), and HTTP/3 to HTTP/2 fallback (`apply_h2_client_auth()`, line ~744). Auth mode enum: `MasqueAuthMode::CloudflareMtls` (`config.rs:7`).
- **Finalmask** on the xHTTP transport family (`ripdpi-xhttp/src/finalmask.rs`). Three modes today: `header_custom` (line 18), `fragment` (line 20), `sudoku` (line 21). Enumeration: `enum FinalmaskSpec` (lines 18-22). Dual-stream bridge pattern with `TcpOutboundMask` / `TcpInboundMask` (lines 112-115). 3 unit tests cover mode encoding/decoding.
- **Signed strategy-pack delivery** (SHA256withECDSA, trusted key id `ripdpi-prod-p256`, schema v3), rollout flags, bundled fallback catalogs, and versioned TLS profile catalogs. See `docs/strategy-pack-operations.md`.
- **Relay credential storage** through `KeystoreRelayCredentialStore` at `core/data/.../RelayStores.kt:142-176` (Android Keystore-backed, alias `ripdpi_relay_credentials`, storage `relay_credentials_secure`). Protects VLESS UUID, Cloudflare Tunnel token and credentials JSON, MASQUE client cert chain and private key PEM, plus transport auth fields.
- **Automatic probing, automatic audit, remembered per-network policies**, and relay/runtime telemetry that feed rollout and validation work.

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
| Relay interoperability matrix | Expand cross-transport test confidence | Unit and service coverage exist for relay config, MASQUE, xHTTP, Finalmask, and helper lifecycles via `scripts/ci/run-rust-relay-interoperability.sh` + `ripdpi-runtime --test network_e2e` (gated on `RIPDPI_RUN_NESTED_PROXY_E2E=1`) | CI coverage closes the remaining provider and transport edge cases without relying on manual hand checks |

### Relay Kind Registry

Runtime-config dispatch now lives in
`core/service/.../UpstreamRelayRuntimeConfigResolver.kt` behind
`RelayKindResolver`. The current split covers MASQUE, Cloudflare Tunnel,
Snowflake, Chain relay, ShadowTLS, NaiveProxy, local-path transports
(`Snowflake`, `WebTunnel`, `Obfs4` family handling), and a default resolver for
the remaining direct relay kinds. Field-validation tracks above must stay
compatible with that resolver boundary.

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
