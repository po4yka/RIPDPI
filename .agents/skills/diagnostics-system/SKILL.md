---
name: diagnostics-system
description: Reference for the two-tier diagnostics pipeline (Rust ripdpi-monitor-engine/ripdpi-diagnostics-*, Kotlin core/diagnostics). Use when changing ScanRequest/ScanReport, ProbeTask families, strategy-probe candidates, the catalog, or the wire contract.
---

# Diagnostics System

## 1. Overview

The diagnostics system is a two-tier pipeline:

- **Rust engine** (`native/rust/crates/ripdpi-monitor-engine/` plus the `ripdpi-diagnostics-*` crates) -- executes network probes in a background thread, producing a `ScanReport` with structured `ProbeResult` and `ProbeObservation` values.
- **Kotlin orchestration** (`core/diagnostics/`) -- manages lifecycle, catalog
  loading, JNI bridge, report enrichment, persistence, and policy application.

The Rust engine is stateless per scan; the Kotlin layer owns state, scheduling,
and cross-scan coordination (remembered network policies, resolver overrides).

Telemetry primitives live in `native/rust/crates/ripdpi-telemetry/` and provide
`LatencyHistogram` and `LatencyDistributions` for in-process latency recording.

## 2. Scan Types

Two `ScanKind` variants exist:

| Kind | Purpose | Typical profile |
|------|---------|-----------------|
| `Connectivity` | Tests reachability of domains, DNS, TCP, QUIC, services, circumvention tools, Telegram, and throughput targets. Results are bucketed into healthy/attention/failed/inconclusive. | `default`, `dpi-detector-full`, `ru-*` profiles |
| `StrategyProbe` | Evaluates path optimization strategy candidates (TCP and QUIC) against a target set, then recommends the best configuration. Requires `RAW_PATH` mode (outside the VPN tunnel). | `automatic-probing` (quick_v1), `automatic-audit` (full_matrix_v1) |

Connectivity scans are general-purpose. Strategy probe scans are the automatic
calibration mechanism -- they test multiple bypass configurations and select
the most effective one for the current network.

## 3. Rust Engine Pipeline

### Entry point

`MonitorSession::start_scan()` in `lib.rs` validates the wire request, spawns
a worker thread, and calls `run_engine_scan()` (in `engine.rs`).

### Stage-based execution model

The engine uses a plan-then-execute architecture:

1. **Plan** (`engine/plan.rs`): `build_execution_plan()` creates an
   `ExecutionPlan` with an ordered `Vec<ExecutionStageId>`. For connectivity
   scans the order is derived from `probe_tasks` families (or a default
   sequence). For strategy probes, `strategy_stage_order()` builds:
   Environment -> (`Web`, only when in-path mode with a route and domain
   targets) -> StrategyDnsBaseline -> `StrategyTcpCandidates`/`StrategyQuicCandidates`
   (QUIC first when `confirm_good_dpi_evidence` is set, TCP first otherwise)
   -> `StrategyConnectionConcurrency` -> `StrategyRecommendation`. Read
   `strategy_stage_order()` directly before restating this order; it is a
   stale-prone list, not a fixed constant.

2. **Coordinate** (`engine/runtime.rs`): `ExecutionCoordinator` holds a
   `BTreeMap<ExecutionStageId, Box<dyn ExecutionStageRunner>>` and iterates
   `plan.stage_order`, invoking each runner. It checks cancellation and
   deadline between stages.

3. **Run** (`engine/runners/`): Each runner implements `ExecutionStageRunner`
   with `id()`, `phase()`, `total_steps()`, and `run()`. Runners produce
   `RunnerArtifacts` (probe results + observations + events) and call
   `runtime.record_step()` which increments progress and publishes it to
   shared state.

4. **Report** (`engine/report.rs`): `build_report()` assembles the final
   `ScanReport` from accumulated results, observations, and optional
   strategy probe report. Includes `engine_analysis_version`,
   `classifier_version`, and `pack_versions`.

### Cancellation and deadlines

`ExecutionRuntime` checks `is_cancelled()` (cooperative via `AtomicBool`) and
`is_past_deadline()` (360s default hard deadline) between stages. A cancelled scan
still produces a partial report.

### Progress reporting

Progress flows through `SharedState` (behind `Arc<Mutex<>>`) and is polled
by Kotlin via `poll_progress_json()`. The `EngineProgressWire` includes
phase, step counts, and optional `StrategyProbeLiveProgress` for candidate-
level tracking.

## 4. Strategy Probe System

This is the most complex subsystem. It automatically selects the best DPI
bypass configuration for the user's network.

### Candidate generation (`candidates.rs`)

`build_strategy_probe_suite()` creates a `StrategyProbeSuite` with TCP and
QUIC candidate lists. Two suites exist:
- `quick_v1` -- subset for background automatic probing
- `full_matrix_v1` -- complete matrix for manual audit

Each candidate is a `StrategyCandidateSpec` with an id, label, family,
eligibility rule, warmup requirements, and a `ProxyUiConfig` describing the
bypass strategy parameters.

#### Candidate inventory

Candidate membership is capability-dependent (TFO, raw-packet and
IP-fragmentation support). Do not copy a fixed list or count into docs. Read
`build_strategy_probe_suite()` and the TCP/QUIC builder families in
`ripdpi-diagnostics-candidates`, then exercise the registry tests for the
runtime capabilities under review.

### DNS baseline (`strategy.rs`)

`detect_strategy_probe_dns_tampering()` runs before any candidate evaluation.
It compares system DNS answers against encrypted DNS (DoH) answers for each
target domain. If DNS tampering is detected (NXDOMAIN or substitution), the
scan short-circuits -- it skips all TCP/QUIC candidates and recommends a
resolver override instead.

The encrypted DNS context is resolved via `strategy_probe_encrypted_dns_context()`
which prefers the user's configured resolver, falling back to AdGuard.

#### DNS fallback for strategy probes

When the primary encrypted resolver fails, `detect_strategy_probe_dns_tampering()`
invokes `build_fallback_encrypted_dns_endpoints()` (in `dns.rs`) to try
alternative resolvers in order: AdGuard, DNS.SB, Google IP, Mullvad. This
prevents a failure of the preferred resolver from being misclassified as
DNS tampering.

### TCP/QUIC candidate evaluation (`engine/runners/strategy.rs`)

For each candidate:
1. Build a `ProxyUiConfig` with the candidate's bypass parameters
2. Probe each target domain (HTTP/HTTPS/QUIC depending on lane)
3. Record success/failure per target, compute weighted quality score
4. Jitter-based pauses between candidates (`candidate_pause_ms()`) to avoid
   network-level rate limiting

#### Tournament bracket (Round 1 qualifier)

Before the full matrix (Round 2), each TCP candidate is tested against a single
representative domain. Candidates that fail both HTTP and HTTPS probes in Round
1 are eliminated and do not enter the full cross-product evaluation. On censored
networks this typically eliminates ~70% of candidates, significantly reducing
total scan time.

Within each candidate's domain set, up to 3 domains are tested concurrently
via `thread::scope` inside `execute_tcp_candidate()`.

### Connection concurrency probing (`StrategyConnectionConcurrencyRunner`)

Runs after TCP/QUIC candidate evaluation and before the recommendation
stage. Opens simultaneous raw TLS connections to eligible targets across
every available TLS profile (`ripdpi_tls_profiles::AVAILABLE_PROFILES`) at
increasing concurrency levels -- `[1, 4]` for a quick/automatic scan,
`[1, 2, 4, 8]` for a full audit -- to detect DPI/middlebox behavior that
only triggers once several connections open at once (a class of
concurrency-based throttling or blocking a single-connection probe cannot
see). Produces a `ConnectionConcurrencyAssessment` per target via
`classify_connection_concurrency_matrix()`. See
`native/rust/crates/ripdpi-monitor-engine/src/engine/runners/strategy/connection_concurrency.rs`.

### Stage timeouts

These are the Kotlin-side timeouts applied when a strategy-probe profile
runs inside the DiagnosticsHome composite run (section 6); a standalone
scan outside that flow is not bound by them. Timeouts are computed from
`HomeCompositeStageDefinitions.kt` (`stageTimeoutMs()`), not passed as a
single flat constant. The strategy
probe budget is a sum of two independently-tunable sub-budgets:
`StrategyProbeStageTimeoutMs = StrategyProbeNativeDeadlineMs (270_000ms)
+ StrategyProbeFinalizationTimeoutMs (60_000ms)` = 330s total. It applies
to the `automatic_audit` and `dpi_strategy` (`ru-dpi-strategy` profile)
stages; `ru-dpi-full` (`dpi_full` stage) uses a separate, shorter
`DpiFullStageTimeoutMs` (240s). Quick-scan mode uses
`QuickScanStrategyProbeNativeDeadlineMs` (60_000ms) instead of the full
270s deadline for `ru-dpi-strategy`. The native engine's own
`scan_deadline_ms` in `ScanRequest` is set from `stageScanDeadlineMs()`,
which for strategy-probe stages returns the native deadline sub-budget
directly (not `stageTimeout - 30s`) so the 60s finalization budget is
reserved for report retrieval after the native deadline fires. Re-derive
these numbers from `HomeCompositeStageDefinitions.kt` rather than citing
the totals above once any of the underlying constants change.

### Partial results recovery

When a strategy probe stage times out, `ActiveScanRegistry.cancelActiveScan()`
does not immediately discard results. Instead it polls for a 3-second grace
period, giving the native engine time to finish writing its current candidate
batch. If a partial report is available it is retrieved and persisted. This is
surfaced as `StrategyProbeCompletionKind::PartialResults` in the finalized
report, indicating the scan was interrupted mid-execution but produced usable
candidate data.

### Recommendation output

`StrategyRecommendationRunner` selects the TCP and QUIC winners by quality
score, produces a `StrategyProbeRecommendation` with the winning candidate
IDs, labels, and recommended `proxyConfigJson`. An `AuditAssessment` is
attached with coverage metrics and confidence level (Low/Medium/High).

## 5. Diagnostics Catalog

The catalog defines what targets and profiles ship with the app.

### Build-time generation

Located in `build-logic/convention/src/main/kotlin/DiagnosticsCatalog*.kt`:

- `DiagnosticsCatalogDomain.kt` -- domain model: `TargetPackDefinition`,
  `DiagnosticsProfileDefinition`, `CatalogScanKind`, enums for profile
  families (GENERAL, WEB_CONNECTIVITY, MESSAGING, CIRCUMVENTION, etc.)
- `DiagnosticsCatalogPackSource.kt` -- `DefaultDiagnosticsCatalogPackSource`
  defines target packs (e.g., `ru-independent-media`, `ru-global-platforms`,
  `ru-messaging`, `ru-circumvention`, `ru-throttling`, `neutral-control`)
- `DefaultDiagnosticsCatalogProfileSource.kt` -- `DefaultDiagnosticsCatalogProfileSource`
  defines profiles that reference packs and configure scan behavior
- `DiagnosticsCatalogAssembler.kt` -- loads packs, loads profiles (with
  pack index for cross-referencing), validates, then renders to JSON
- `DiagnosticsCatalogDefinitions.kt` -- top-level entry point calling the
  assembler

### How to add a new target pack

1. Add a `TargetPackDefinition` to `DefaultDiagnosticsCatalogPackSource.load()`
   with an id, version, and target lists (domain, DNS, TCP, QUIC, etc.)
2. Reference it from profiles via `index.requirePack("your-pack-id")`

### How to add a new diagnostic profile

1. Add a function to `DefaultDiagnosticsCatalogProfileSource` that returns
   a `DiagnosticsProfileDefinition`
2. Include it in the `load()` list
3. Set `kind`, `family`, `executionPolicy`, `packRefs`, and target lists
4. For strategy probe profiles: set `kind = CatalogScanKind.STRATEGY_PROBE`
   and provide a `StrategyProbeDefinition` with the suite ID
5. Rebuild to regenerate `default_profiles.json` asset

## 6. DiagnosticsHome Composite Run

`core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/HomeCompositeStageDefinitions.kt`
is the source of truth for both stage lists below; read it directly
rather than trusting this table once stages are added, removed, or
reordered.

### `HomeCompositeStageSpecs` (full run, 9 stages)

| Stage key | Profile | Notes |
|-----------|---------|-------|
| `automatic_audit` | `automatic-audit` (full_matrix_v1 strategy probe) | `StrategyProbeStageTimeoutMs` (330s); see "Stage timeouts" above |
| `detection_signals` | `detection-signals` | `DETECTION_SIGNALS` kind; `DetectionStageTimeoutMs` (90s) |
| `default_connectivity` | `default` | Standard connectivity check |
| `ru_throttling` | `ru-throttling` | `ThrottlingStageTimeoutMs` (240s) |
| `ru_circumvention` | `ru-circumvention` | Sensitive-services reachability; `SensitiveServicesStageTimeoutMs` (240s) |
| `dpi_full` | `ru-dpi-full` | Full DPI detection sweep; `DpiFullStageTimeoutMs` (240s) -- **not** `dpi-detector-full` and **not** the 330s strategy-probe budget |
| `path_comparison` | `path-comparison` | IN_PATH mode (proxy vs. direct); `PathComparisonStageTimeoutMs` (180s) |
| `vpn_route_evidence` | `vpn-route-evidence` | Passive VPN-route evidence only; does not start a scan session (`startsScanSession = false`) |
| `dpi_strategy` | `ru-dpi-strategy` | `STRATEGY_PROBE` (full_matrix_v1) scoped to Russian-domain target packs; shares `StrategyProbeStageTimeoutMs` (330s) with `automatic_audit` |

### `QuickScanStageSpecs` (reduced run, 4 stages)

A separate list used for the quick/automatic (non-manual-audit) path:
`automatic_audit`, `detection_signals`, `vpn_route_evidence`,
`dpi_strategy` -- the same specs as above, but `dpi_strategy` uses
`QuickScanStrategyProbeNativeDeadlineMs` (60s) instead of the full 270s
native deadline (see "Stage timeouts" above).

## 7. Kotlin Orchestration Layer

### Call chain

```
DiagnosticsScanController.startScan()
  -> ScanAdmissionService.admitManualStart()  -- checks no active scan
  -> DiagnosticsScanRequestFactory.prepareScan()  -- builds PreparedDiagnosticsScan
  -> BridgeExecutionService.createHandle()  -- creates JNI bridge
  -> BridgeExecutionService.start()  -- calls bridge.startScan(requestJson)
  -> DiagnosticsScanExecutionCoordinator.execute()  -- launched in coroutine
      -> BridgePollingService.awaitCompletion()  -- polls progress + report
      -> ScanFinalizationService.finalize()  -- enriches, persists, applies policies
```

### Key classes

| Class | Responsibility |
|-------|---------------|
| `DefaultDiagnosticsScanController` | Entry point for manual and automatic scans. Manages hidden probe conflicts. |
| `ScanAdmissionService` | Guards against concurrent scans. Resolves profile from settings. |
| `ActiveScanRegistry` | Tracks active bridges, execution jobs, cancellation state, fingerprints. Implements 3s grace-period partial results polling on cancel. |
| `BridgeExecutionService` | Creates and destroys `NetworkDiagnosticsBridge` (JNI). |
| `BridgePollingService` | Polls `pollProgressJson()`/`takeReportJson()` on interval. Timeout: 360s. |
| `ScanFinalizationService` | Enriches report (classifier, resolver recommendation, strategy validation), persists results, applies remembered network policies. |
| `DiagnosticsScanWorkflow` | Pure-logic orchestration: report enrichment, resolver override decisions, background auto-persist eligibility, network policy construction. |
| `RuntimeSessionCoordinator` | Manages bypass usage sessions (not scan sessions). Tracks connection lifecycle, telemetry sampling, failure recording. |
| `DiagnosticsScanRequestFactory` | Builds wire-format request JSON from profile + settings. |

### Automatic probing

`AutomaticProbeCoordinator` and `AutomaticProbeScheduler` trigger background
scans on policy handover events. These use `launchAutomaticProbe()` on the
controller. Background probes run hidden (no UI progress) and auto-persist
results when audit confidence is high enough (coverage >= 75%, winner coverage
>= 50%).

### DNS-corrected re-probe

When a strategy probe is short-circuited by DNS tampering and a temporary
resolver override is applied, the system automatically re-probes after a 2s
delay to evaluate candidates with corrected DNS.

## 8. Logcat Capture

Logcat collection uses two capture scopes:

| Scope | Flag | Use case |
|-------|------|----------|
| `app_visible_snapshot` | legacy (no `-T`) | Short scans where log rotation is not a concern |
| `time_bound_snapshot` | `-T <timestamp>` | Long-running scans (e.g., strategy probe stages) |

The `time_bound_snapshot` scope passes the `-T sinceTimestampMs` flag set to
the earliest session start time across all active sessions. This prevents log
rotation loss on scans that exceed the default logcat ring-buffer window.

## 9. Wire Protocol

Rust and Kotlin communicate via JSON serialization over JNI. The wire types
mirror each other (`EngineScanRequestWire`, `EngineProgressWire`,
`EngineScanReportWire`, plus the embedded result/observation types); see
`references/wire-protocol.md` for the full type table and field-level detail.

The schema version constant is defined once, in the `ripdpi-diagnostics-contracts`
crate (`native/rust/crates/ripdpi-diagnostics-contracts/src/wire.rs`,
`DIAGNOSTICS_ENGINE_SCHEMA_VERSION`); `ripdpi-monitor-engine`'s own `wire.rs`
only re-exports it. Kotlin mirrors it as `DiagnosticsEngineSchemaVersion`
(`contract/engine/EngineContract.kt`). Both must be equal; this is enforced
by contract tests. Re-verify the current value with
`grep -n DIAGNOSTICS_ENGINE_SCHEMA_VERSION native/rust/crates/ripdpi-diagnostics-contracts/src/wire.rs`
rather than trusting a copied number -- it is 9 as of this writing and has
moved before.

The `ScanRequest` field `scan_deadline_ms` is optional. The Rust engine's own
internal default when it is absent is 360,000ms (360s -- the same value as
the `is_past_deadline()` hard deadline in "Cancellation and deadlines"
above), not a stage-timeout-derived number. A caller going through the
DiagnosticsHome composite run instead gets an explicit value from Kotlin's
`stageScanDeadlineMs()` (see "Stage timeouts" in section 4), which for most
non-strategy-probe stages is approximately `stageTimeout - 30s` but for
`automatic_audit`/`ru-dpi-strategy` is the native deadline sub-budget
directly -- do not conflate the two defaults.

## 10. Adding a New Probe Type

End-to-end walkthrough for adding a hypothetical "ping" probe:

### Rust side

1. **Define target type** in `types/target.rs`:
   ```rust
   pub struct PingTarget { pub host: String, pub count: u8 }
   ```
2. **Add to ScanRequest** in `types/request.rs`:
   ```rust
   pub ping_targets: Vec<PingTarget>,
   ```
3. **Add wire mapping** in `wire.rs` -- add field to `EngineScanRequestWire`,
   update `From<EngineScanRequestWire> for ScanRequest`.
4. **Add ProbeTaskFamily variant** in `types/request.rs`:
   ```rust
   Ping,
   ```
5. **Create execution stage** -- add `ExecutionStageId::Ping` in
   `engine/runtime.rs`. Create `PingRunner` implementing
   `ExecutionStageRunner` in `engine/runners/connectivity.rs`.
6. **Register runner** in `engine/runners/mod.rs`:
   ```rust
   Box::new(PingRunner),
   ```
7. **Add stage to plan** in `engine/plan.rs` -- add
   `ProbeTaskFamily::Ping => ExecutionStageId::Ping` mapping and include
   in the default connectivity order.
8. **Add observation mapping** in `observations.rs` if structured facts
   are needed.
9. **Update contract fixtures** -- add outcome tokens, update field manifests.

### Kotlin side

1. **Add target model** in `core/diagnostics/.../Models.kt`.
2. **Mirror wire types** in `contract/engine/EngineContract.kt` -- add to
   `EngineScanRequestWire` and `EngineProbeTaskFamily`.
3. **Add to catalog domain** in `DiagnosticsCatalogDomain.kt` -- add target
   definition type and include in `TargetPackDefinition`.
4. **Update catalog rendering** to serialize the new target list.
5. **Add contract fixture** entries and update golden tests.

### Tests

- Add outcome tokens to `outcome_taxonomy_current.json`.
- Rust: `contract_fixtures.rs` will catch missing outcomes.
- Kotlin: `DiagnosticsWireContractTest` will catch field mismatches.

## 11. Adding a New Diagnostic Profile

1. Open `build-logic/convention/src/main/kotlin/DefaultDiagnosticsCatalogProfileSource.kt`.
2. Add a private function returning `DiagnosticsProfileDefinition`:
   ```kotlin
   private fun myNewProfile(index: DiagnosticsCatalogIndex): DiagnosticsProfileDefinition {
       val myPack = index.requirePack("my-pack")
       return DiagnosticsProfileDefinition(
           id = "my-new-profile",
           name = "My New Profile",
           family = CatalogDiagnosticProfileFamily.GENERAL,
           executionPolicy = policy(manualOnly = false, allowBackground = false, requiresRawPath = false),
           domainTargets = myPack.domainTargets,
           // ...
       )
   }
   ```
3. Add it to the `load()` list.
4. If you need a new target pack, add it to `DiagnosticsCatalogPackSource.kt`.
5. Rebuild. The catalog assembler validates uniqueness, pack references, and
   schema constraints.
6. Update `diagnostics-contract-fixtures/profile_catalog_current.json` by
   running contract tests with `RIPDPI_BLESS_GOLDENS=1`.

## 12. Testing

### Golden contract tests

The contract test framework ensures Rust and Kotlin stay in sync:

- **Shared fixtures** in `contract-fixtures/` (repo root) -- schema version,
  field manifests for progress and report types.
- **Diagnostics fixtures** in `diagnostics-contract-fixtures/` -- full wire
  payloads for request, report, progress, profile catalog, outcome taxonomy.
- **Rust side** (`tests/contract_fixtures.rs`): decodes all shared fixtures,
  verifies schema version matches, checks outcome tokens cover all emitted
  probe outcomes.
- **Kotlin side** (`DiagnosticsContractGovernanceTest.kt`): decodes the same
  fixtures, verifies schema version, checks bundled catalog matches fixture.
- **Wire field tests** (`DiagnosticsWireContractTest.kt`): compares field
  paths between Rust-produced manifests and Kotlin serialization.

### Golden file support

`GoldenContractSupport.kt` provides `assertJsonGolden()` and
`assertTextGolden()`. Set `RIPDPI_BLESS_GOLDENS=1` to regenerate. Diff
artifacts are written to `core/diagnostics/build/golden-diffs/`.

### Key test classes

| Test | What it verifies |
|------|-----------------|
| `DiagnosticsWireContractTest` | Field-level compatibility between Rust and Kotlin wire types |
| `DiagnosticsContractGovernanceTest` | Schema versions match, fixtures decode, catalog matches asset |
| `DiagnosticsScanWorkflowTest` | Strategy probe enrichment, background eligibility, policy construction |
| `DiagnosticsScanControllerTest` | Scan lifecycle: start, cancel, hidden probe conflict resolution |
| `DiagnosticsScanExecutionCoordinatorTest` | Execution flow: polling, finalization, DNS-corrected re-probe |
| `DiagnosticsScanRequestFactoryTest` | Wire request construction from profiles and settings |
| `DiagnosticsModelsCompatibilityTest` | Model serialization round-trip stability |

### Wire compatibility verification

After any wire type change:
1. Run `cargo test --locked -p ripdpi-monitor-engine` -- catches fixture decode failures and
   outcome token coverage gaps.
2. Run `:core:diagnostics:testDebugUnitTest` -- catches field manifest and
   schema version mismatches.
3. If fields were added: bless goldens with `RIPDPI_BLESS_GOLDENS=1` and
   commit updated fixtures.

## 13. PCAP Diagnostic Recording

On-device packet capture for DPI evasion debugging without external tools.

**Rust components:**
- `ripdpi-monitor-engine` / diagnostics archive support -- standard pcap export, retention, and archive bundling for captured sessions
- `ripdpi-proxy-runtime/src/runtime/state/desync.rs` plus the runtime desync context -- `PcapHook` callback invoked on outbound desync execution
- `ripdpi-android/src/ffi/proxy_bridge/pcap.rs` -- JNI bridge: `jniStartPcapRecording`, `jniStopPcapRecording`, `jniIsPcapRecording`

**Android components:**
- `DiagnosticsViewModel` -- `togglePcapRecording()` action, `pcapRecording` state flow
- `DiagnosticsArchiveFileStore` -- `cleanupPcapFiles()` (24h age-based), `getRecentPcapFiles()` for export
- `DiagnosticsArchiveRenderer` -- includes up to 3 recent pcap files in diagnostic export bundles

**Storage safety:** 10 MB file cap, 50 connection auto-stop, cache directory storage, 24h cleanup on app start.
