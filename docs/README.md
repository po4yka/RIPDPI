# Documentation

RIPDPI documentation index. For a quick start, see the main [README](../README.md).

## Find docs by task

Choose the row matching the change or failure you are investigating. Read the entry point and its prerequisites, then follow the next pointer for the affected part of the task. The topic catalog below remains available for broader reading.

| Task | Start here | Follow when needed |
| --- | --- | --- |
| Fix a local build, slow Gradle/Cargo build, or worktree contention | [Build performance](contributor/build-performance.md) | [Checkout discovery and setup](../AGENTS.md#source-of-truth); current build inputs in [Gradle properties](../gradle.properties) and the [Rust workspace manifest](../native/rust/Cargo.toml) |
| Diagnose a failed CI job | [Short CI triage route](contributor/ci-triage.md) | Exact run/attempt/job logs, then the failing workflow's command and artifacts |
| Select tests | [Testing](testing.md) | [Feature test checklist](feature-test-checklist.md) for feature and combination coverage |
| Change module ownership or Rust crate boundaries | [Architecture overview](architecture/ARCHITECTURE.md) | [Native Rust workspace](architecture/NATIVE_RUST.md) for layering; [architecture quality gates](architecture/quality-gates.md) for validation |
| Change a Kotlin/Rust payload, JNI method, or wire schema | [JNI contract](architecture/JNI_CONTRACT.md) | [Config contracts](architecture/CONFIG_CONTRACTS.md) for serialization and compatibility; [diagnostics architecture](architecture/DIAGNOSTICS_ARCHITECTURE.md) for scan payloads |
| Add or change a persisted setting | [Config contracts](architecture/CONFIG_CONTRACTS.md) | [Protobuf/DataStore mapping](../.agents/skills/protobuf-datastore/SKILL.md); [protobuf schema evolution](../.agents/skills/protobuf-schema-evolution/SKILL.md) for field changes |
| Investigate VPN/proxy startup, shutdown, or network handover | [Runtime modes](architecture/RUNTIME_MODES.md) | [Service lifecycle](../.agents/skills/service-lifecycle/SKILL.md) and [service session scope](service-session-scope.md) for ownership; [runtime debugging](native/debug-runtime-issue.md) for evidence collection |
| Change a diagnostics stage, target catalog, or report | [Diagnostics architecture](architecture/DIAGNOSTICS_ARCHITECTURE.md) | [Diagnostics system](../.agents/skills/diagnostics-system/SKILL.md) for implementation paths and contract tests |
| Add a strategy, relay, or probe | [Architecture overview](architecture/ARCHITECTURE.md) | [Feature extension guide](architecture/FEATURE_EXTENSION_GUIDE.md), after its native-workspace prerequisites, for the relevant extension checklist |
| Change a Compose screen, theme, or localized text | [Design spec](../DESIGN.md) | [Design system](design-system.md) and [RIPDPI Compose patterns](../.agents/skills/android-compose-patterns/SKILL.md); [locale rules](../AGENTS.md#locales) for resource changes |
| Author or debug Appium/Maestro UI automation | [External UI automation](automation/README.md) | [Selector contract](automation/selector-contract.md) for locators; [Appium readiness](automation/appium-readiness.md) for setup and execution |
| Build, sign, or publish a release | [Release workflow](../.agents/skills/ripdpi-release/SKILL.md) | [Signing](../.agents/skills/release-signing/SKILL.md) and [distribution channels](distribution.md) for release inputs and channel behavior |
| Change agent instructions, skills, mirrors, hooks, or their checks | [Harness maintenance](../.claude/rules/harness-maintenance.md) | [Harness manifests](../scripts/ci/check_harness_manifests.py) and [reference audit](../scripts/ci/check_harness_links.py) for the enforced rules |
| Create, execute, or close a portfolio task; decide whether OpenSpec is required | [Task management](tasks/README.md) | [Task board workflow](../.agents/skills/repo-task-board/SKILL.md) for the repository CLI and execution routes |

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

- [Short CI triage route](contributor/ci-triage.md)
- [Feature test checklist](feature-test-checklist.md)
- [Testing, E2E, golden contracts, and soak coverage](testing.md)
- [Strict local VPN acceptance](../test-lab/acceptance/README.md)
- [Local network test lab](../test-lab/README.md)
- [Local network lab coverage](../test-lab/SPEC.md)
- [Android logcat filtering](android-logcat-filtering.md)

## Architecture Hardening

- [Architecture notes](architecture/README.md)
- [Current roadmap](../ROADMAP.md)
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
