# Integrations Roadmap - Phase P3 Implementation

This phase is the long-horizon evasive-transport and detection-resistance track. It should remain gated behind earlier transport stability work and should bias toward experiments, feature flags, and updateable delivery mechanisms.

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

## Recommended Sequence

1. Promote the current fingerprint system into an updateable profile catalog. P0 should make Chrome the default everywhere; P3 should add profile freshness, versioned bundles, and controlled rotation without regressing the deterministic behavior already present in `ripdpi-tls-profiles`.

2. Build signed, updateable strategy packs before adding more speculative evasion behavior. Item 15 is the delivery mechanism that keeps future traffic morphing, hostlists, and transport knobs out of hardcoded releases.

3. Add adaptive traffic morphing only after strategy packs exist. Morphing should be policy-driven, measured, and remotely tunable because it touches packet sizes, timing, and entropy targets that will need field adjustments.

4. Keep QUIC migration as an experimental branch, not a default feature. It depends on VPN-mode packet ownership, transport support, and careful fallback logic, so it should stay behind explicit feature flags and diagnostics gates.

5. Treat pluggable transports as subprocess-based contingency tooling first. Snowflake, WebTunnel, and obfs4 are valuable last-resort options, but they should land as isolated runtimes with clear packaging and lifecycle boundaries rather than being deeply fused into the main native relay core on day one.

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
