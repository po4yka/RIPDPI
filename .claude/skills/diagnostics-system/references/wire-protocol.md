# Wire Protocol Reference

## Schema Version Tracking

The wire protocol version is a single integer constant maintained in two places:

| Location | Constant | Language |
|----------|----------|----------|
| `native/rust/crates/ripdpi-monitor/src/wire.rs` | `DIAGNOSTICS_ENGINE_SCHEMA_VERSION: u32 = 1` | Rust |
| `core/diagnostics/src/main/kotlin/.../contract/engine/EngineContract.kt` | `DiagnosticsEngineSchemaVersion = 1` | Kotlin |

These must always be equal. The contract governance test
`DiagnosticsContractGovernanceTest::engine schema version matches rust contract constant`
reads the Rust source file directly and asserts equality with the Kotlin constant.

### When to bump the version

Bump `DIAGNOSTICS_ENGINE_SCHEMA_VERSION` when making backward-incompatible
changes to wire types (removing fields, renaming fields, changing field
semantics). Adding new optional fields with `#[serde(default)]` is backward-
compatible and does not require a version bump.

## Wire Types

### EngineScanRequestWire (Kotlin -> Rust)

Serialized as JSON by Kotlin, deserialized by Rust via `serde_json`.

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `schemaVersion` | int/u32 | Yes (default: current) | Used for future migration |
| `profileId` | string | Yes | |
| `displayName` | string | Yes | |
| `pathMode` | enum | Yes | `RAW_PATH` or `IN_PATH` |
| `kind` | enum | No (default: `CONNECTIVITY`) | `CONNECTIVITY` or `STRATEGY_PROBE` |
| `family` | enum | No (default: `GENERAL`) | Profile family discriminator |
| `regionTag` | string? | No | |
| `packRefs` | string[] | No | Referenced target pack IDs |
| `proxyHost` | string? | No | SOCKS proxy for IN_PATH mode |
| `proxyPort` | int? | No | |
| `probeTasks` | ProbeTask[] | No | When present, limits stages to matching families |
| `domainTargets` | DomainTarget[] | No | |
| `dnsTargets` | DnsTarget[] | No | |
| `tcpTargets` | TcpTarget[] | No | |
| `quicTargets` | QuicTarget[] | No | |
| `serviceTargets` | ServiceTarget[] | No | |
| `circumventionTargets` | CircumventionTarget[] | No | |
| `throughputTargets` | ThroughputTarget[] | No | |
| `whitelistSni` | string[] | No | SNIs for fat header comparison |
| `telegramTarget` | TelegramTarget? | No | |
| `strategyProbe` | StrategyProbeRequest? | No | Required when kind = STRATEGY_PROBE |
| `networkSnapshot` | NetworkSnapshot? | No | OS network state |
| `nativeLogLevel` | string? | No | e.g., "debug", "trace" |
| `logContext` | LogContext? | No | Correlation context |

### EngineProgressWire (Rust -> Kotlin)

Polled by Kotlin via `MonitorSession::poll_progress_json()`.

| Field | Type | Notes |
|-------|------|-------|
| `schemaVersion` | u32/int | |
| `sessionId` | string | |
| `phase` | string | Stage name: "environment", "dns", "web", "tcp", "quic", etc. |
| `completedSteps` | usize/int | Monotonically increasing |
| `totalSteps` | usize/int | Sum of all runner step counts |
| `message` | string | Human-readable status |
| `isFinished` | bool | When true, report is ready |
| `latestProbeTarget` | string? | Last probed target name |
| `latestProbeOutcome` | string? | Last probe outcome token |
| `strategyProbeProgress` | StrategyProbeLiveProgress? | Candidate-level progress for strategy probes |

### EngineScanReportWire (Rust -> Kotlin)

Retrieved by Kotlin via `MonitorSession::take_report_json()`.

| Field | Type | Notes |
|-------|------|-------|
| `schemaVersion` | u32/int | |
| `sessionId` | string | |
| `profileId` | string | |
| `pathMode` | enum | |
| `startedAt` | u64/long | Epoch millis |
| `finishedAt` | u64/long | Epoch millis |
| `summary` | string | e.g., "31 completed . 30 healthy . 1 failed" |
| `results` | EngineProbeResultWire[] | |
| `resolverRecommendation` | ResolverRecommendationWire? | Populated by Kotlin enrichment, not Rust |
| `strategyProbeReport` | StrategyProbeReport? | Present for strategy probe scans |
| `observations` | ObservationFact[] | Structured observation facts |
| `engineAnalysisVersion` | string? | Currently "observations_v1" |
| `diagnoses` | Diagnosis[] | Populated by Kotlin classifier |
| `classifierVersion` | string? | Kotlin-side classifier version |
| `packVersions` | Map<string, int> | Target pack versions used |

### EngineProbeResultWire

| Field | Type | Notes |
|-------|------|-------|
| `probeType` | string | e.g., "dns_integrity", "domain_reachability", "tcp_fat_header" |
| `target` | string | Target identifier |
| `outcome` | string | Outcome token (must be in outcome taxonomy) |
| `details` | ProbeDetail[] | Key-value metadata pairs |
| `probeRetryCount` | int? | |

### ResolverRecommendationWire

| Field | Type | Notes |
|-------|------|-------|
| `triggerOutcome` | string | What triggered the recommendation |
| `selectedResolverId` | string | e.g., "cloudflare" |
| `selectedProtocol` | string | "doh", "dot", "dnscrypt" |
| `selectedEndpoint` | string | |
| `selectedBootstrapIps` | string[] | |
| `selectedHost` | string | |
| `selectedPort` | int | |
| `selectedTlsServerName` | string | |
| `selectedDohUrl` | string | |
| `selectedDnscryptProviderName` | string | |
| `selectedDnscryptPublicKey` | string | |
| `rationale` | string | |
| `appliedTemporarily` | bool | |
| `persistable` | bool | |

## Serialization Format

- **Format**: JSON with `camelCase` field naming (`#[serde(rename_all = "camelCase")]`
  in Rust, `@Serializable` with kotlinx.serialization in Kotlin).
- **Nulls**: Optional fields use `#[serde(default)]` in Rust and default values
  in Kotlin. Nulls are generally omitted via `skip_serializing_if`.
- **Enums**: Serialized as `SCREAMING_SNAKE_CASE` strings (Rust:
  `#[serde(rename_all = "SCREAMING_SNAKE_CASE")]`, Kotlin: enum names).

## Backward Compatibility Rules

1. **Adding optional fields** with `#[serde(default)]` / Kotlin defaults is
   always safe. No version bump needed.
2. **Removing fields** requires a schema version bump. Both sides must handle
   the old version.
3. **Renaming fields** is a breaking change. Prefer adding the new name and
   deprecating the old.
4. **Changing enum variants** (adding is safe with `default` fallback;
   removing is breaking).
5. The Kotlin side deserializes with `ignoreUnknownKeys = true` in the
   diagnostics JSON instance, so unknown fields from newer Rust versions
   are safely ignored.

## Golden Contract Test Locations

### Shared fixtures (repo root)

| File | Purpose |
|------|---------|
| `contract-fixtures/diagnostics_schema_version.json` | Asserts schema version constant |
| `contract-fixtures/diagnostics_progress_fields.json` | Field path manifest for EngineProgressWire |
| `contract-fixtures/diagnostics_scan_report_fields.json` | Field path manifest for EngineScanReportWire |

### Diagnostics-specific fixtures

| File | Purpose |
|------|---------|
| `diagnostics-contract-fixtures/engine_request_current.json` | Full EngineScanRequestWire example |
| `diagnostics-contract-fixtures/engine_report_current.json` | Full EngineScanReportWire example |
| `diagnostics-contract-fixtures/engine_progress_current.json` | Full EngineProgressWire example |
| `diagnostics-contract-fixtures/profile_catalog_current.json` | Bundled catalog (must match asset) |
| `diagnostics-contract-fixtures/outcome_taxonomy_current.json` | All valid outcome tokens |

### Rust contract tests

`native/rust/crates/ripdpi-monitor/tests/contract_fixtures.rs`:
- `shared_contract_fixtures_decode_successfully` -- all fixtures deserialize
- `engine_schema_version_matches_kotlin_contract_constant` -- reads Kotlin source
- `outcome_fixture_covers_emitted_native_outcome_tokens` -- scans Rust probe
  functions for quoted outcome strings and asserts they match the taxonomy

### Kotlin contract tests

`core/diagnostics/src/test/kotlin/.../DiagnosticsContractGovernanceTest.kt`:
- `shared contract fixtures decode successfully` -- all fixtures deserialize
- `bundled catalog fixture matches committed asset and schema version`
- `engine schema version matches rust contract constant` -- reads Rust source

`core/diagnostics/src/test/kotlin/.../DiagnosticsWireContractTest.kt`:
- `diagnostics schema version matches contract fixture`
- `kotlin diagnostics progress fields match contract fixture`
- `kotlin diagnostics report fields are superset of contract fixture`

### Blessing goldens

Set `RIPDPI_BLESS_GOLDENS=1` environment variable when running Kotlin tests
to regenerate golden files. Rust fixtures use the `golden_test_support` crate
with `assert_contract_fixture()` which also supports blessing.

### Diff artifacts

When a golden test fails, diff artifacts are written to
`core/diagnostics/build/golden-diffs/` with `.expected`, `.actual`, and
`.diff` files for each mismatched golden.
