# Integrations Roadmap - Phase P3 Implementation

This phase is the long-horizon evasive-transport and detection-resistance track. It should remain gated behind earlier transport stability work and should bias toward experiments, feature flags, and updateable delivery mechanisms.

## Current Closure Status

Phase P3 is implemented in-repo.

- TLS profile selection and rotation ship through `native/rust/crates/ripdpi-tls-profiles`, with browser-profile catalogs and deterministic rotation support.
- Signed strategy-pack refresh, checksum verification, key pinning, compatibility checks, bundled fallback catalogs, feature flags, and cached snapshots are implemented in the app strategy-pack stack.
- Adaptive traffic-morphing primitives are present in the native config/runtime bridge through morph policies, entropy controls, cadence controls, and diagnostics-facing summaries.
- QUIC migration is wired into the config model and transport path behind explicit flags rather than being treated as a future-only note.
- Pluggable transports are implemented as subprocess-managed relay kinds for Snowflake, WebTunnel, and obfs4, with Android-side lifecycle management and config UI support.

## Scope

- [Item 13 - TLS fingerprint mimicry](roadmap-integrations.md#13-tls-fingerprint-mimicry)
- [Item 14 - Adaptive traffic morphing](roadmap-integrations.md#14-adaptive-traffic-morphing)
- [Item 15 - Updatable strategy packs](roadmap-integrations.md#15-updatable-strategy-packs)
- [Item 16 - QUIC connection migration exploitation](roadmap-integrations.md#16-quic-connection-migration-exploitation)
- [Item 38 - Snowflake, WebTunnel, obfs4 pluggable transports](roadmap-integrations.md#38-snowflake--webtunnel--obfs4-pluggable-transports)

## Current Repo Footing

- TLS profile work already has a base in `native/rust/crates/ripdpi-tls-profiles`, including multiple browser profiles and deterministic rotation. P3 should extend that system, not replace it.
- JA3 observation already exists in `native/rust/crates/ripdpi-monitor`, which gives this phase a way to measure how closely new profiles or morphing behavior match expectations.
- Strategy-pack modeling already exists in `core/data/src/main/kotlin/com/poyka/ripdpi/data/StrategyPackSettings.kt`; the missing piece is secure delivery, versioning, and rollout policy.
- The app already has the relay and transport plumbing needed to host future experiments behind flags, which is important because every item in this phase has a higher blast radius than the earlier phases.

## Remaining Follow-Up Scope

No open P3 roadmap item remains as an unimplemented feature. The remaining work is operational and evolutionary:

- TLS/browser fingerprint catalogs still need ongoing freshness updates as upstream browser fingerprints move.
- Strategy-pack contents, rollout percentages, and feature-flag defaults will continue to evolve even though the signed delivery mechanism is already implemented.
- QUIC migration and pluggable transports remain features that may stay disabled or narrowly rolled out by pack or preset, but that is rollout policy rather than missing implementation.
- Future work in this phase should be treated as iterative tuning, additional presets, and transport hardening rather than a backlog of unbuilt P3 capabilities.

## Repo Touchpoints

- TLS and fingerprinting: `native/rust/crates/ripdpi-tls-profiles`, `native/rust/crates/ripdpi-monitor`
- Strategy delivery: `core/data/src/main/kotlin/com/poyka/ripdpi/data/StrategyPackSettings.kt`, `core/data/src/main/kotlin/com/poyka/ripdpi/data/AppSettingsRepository.kt`
- Native transport experiments: `native/rust/crates/ripdpi-runtime`, `native/rust/crates/ripdpi-tunnel-core`, future experimental crates under `native/rust/crates`
- UI and flags: `app/src/main/kotlin/com/poyka/ripdpi/activities/SettingsViewModel.kt`, `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/settings`

## Exit Criteria

- TLS profile updates can ship independently from full app releases.
- Strategy packs can deliver new profiles, hostlists, and evasion knobs safely.
- Traffic morphing and QUIC migration are measurable and rollbackable.
- Last-resort pluggable transports can be enabled without destabilizing the core relay stack.

All P3 exit criteria above are now satisfied in-repo.
