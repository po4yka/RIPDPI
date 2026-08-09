# Documentation

RIPDPI documentation index. For a quick start, see the main [README](../README.md).

## Architecture — start here

New developers should read these in order:

1. [Architecture overview](architecture/ARCHITECTURE.md) — what RIPDPI is, the module map, the control/data-plane boundary
2. [Runtime modes](architecture/RUNTIME_MODES.md) — proxy, VPN/TUN, diagnostics, relay, optional root helper
3. [Native Rust workspace](architecture/NATIVE_RUST.md) — crate taxonomy and dependency direction
4. [JNI contract](architecture/JNI_CONTRACT.md) — the Kotlin ↔ Rust boundary
5. [Config contracts](architecture/CONFIG_CONTRACTS.md) — protobuf, native JSON, and Rust config compatibility
6. [Feature extension guide](architecture/FEATURE_EXTENSION_GUIDE.md) — adding strategies, relays, probes, settings

[Architecture notes](architecture/README.md) holds the compact, topic-specific ownership records behind these docs.

[Architecture decision records](adr/README.md) index settled protocol decisions, including the Snowflake native Rust no-go and the VLESS Reality ECH policy.

## Native Libraries

- [Native integration and modules](native/README.md)
- [Packet strategy runtime](packet-strategy-runtime.md)
- [Proxy engine and strategy surface](native/proxy-engine.md)
- [TUN-to-SOCKS bridge](native/tunnel.md)
- [Debug a runtime issue](native/debug-runtime-issue.md)
- [Cloudflare Tunnel operations](native/cloudflare-tunnel-operations.md)
- [MASQUE conformance audit](../native/rust/crates/ripdpi-masque/CONFORMANCE.md)
- [NaiveProxy runtime](native/relay-naiveproxy-runtime.md)
- [Finalmask compatibility and example configs](native/finalmask-compatibility.md)

## Operations

- [Strategy-pack and TLS catalog operations](strategy-pack-operations.md)
- [Strategy-pack authoring notes](strategy-packs.md)
- [Offline analytics pipeline](offline-analytics-pipeline.md)
- [TLS catalog refresh log](strategy-pack-tls-refresh-log.json)
- [TLS template acceptance report](tls-template-acceptance-report.json)
- [Android distribution channels](distribution.md)
- [Logging conventions](logging-conventions.md)
- [Server hardening for self-hosted relays](server-hardening.md)

## Configuration

- [Relay profile examples](relay-profile-examples.md)
- [AmneziaWG URI scheme](amneziawg-uri-scheme.md)
- [Support settings deep links](support-settings-deep-links.md)

## Testing & CI

- [Feature test checklist](feature-test-checklist.md)
- [Testing, E2E, golden contracts, and soak coverage](testing.md)
- [Local network test lab](../test-lab/README.md)
- [Local network lab coverage](../test-lab/SPEC.md)
- [Android logcat filtering](android-logcat-filtering.md)

## Architecture Hardening

- [Architecture notes](architecture/README.md)
- [Current task board](tasks/board.md)
- [Architecture quality gates](architecture/quality-gates.md)
- [Unsafe audit guide](native/unsafe-audit.md)
- [Service session scope](service-session-scope.md)
- [TCP relay concurrency](native/tcp-concurrency.md)
- [Native size monitoring](native/size-monitoring.md)

## UI & Design

- [Portable design spec](../DESIGN.md)
- [Design system](design-system.md)
- [Host-pack presets](host-pack-presets.md)

## Automation

- [External UI automation](automation/README.md)
- [Selector contract](automation/selector-contract.md)
- [Appium readiness](automation/appium-readiness.md)
- [Maestro smoke flows](../maestro/README.md)

## User Manuals

- [Diagnostics manual (Russian)](user-manual-diagnostics-ru.md)
