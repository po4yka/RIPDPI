# ROADMAP: Architecture Refactor And Modularization

> Execution roadmap derived from the 2026-04 architecture audit.
> This roadmap focuses on structural refactoring, ownership boundaries,
> modularization, and maintainability. It is separate from the completed audit
> roadmap and from the bypass-technique modernization roadmap.

## Design Direction

The audit's main conclusions are the planning baseline for this roadmap:

- Re-establish single ownership for critical policy surfaces:
  - proxy/runtime config contract
  - diagnosis/classification rules
  - service lifecycle and restart policy
- Split internal mini-monoliths before introducing more features.
- Prefer composition and typed section boundaries over base classes, giant
  reducers, or global switchboards.
- Treat file/function size as a signal, not the primary goal. The real target is
  clearer responsibility boundaries and lower change surface.
- Add guardrails so architecture debt stops regrowing after the refactor.

## Refactor Principles

- Preserve user-visible behavior while moving boundaries.
- Do not combine large refactors with feature work in the same change.
- Do not split modules or crates only to reduce line counts; each extraction must
  create a clear ownership seam.
- Prefer temporary adapters and facades over flag-day rewrites.
- Every phase must end with tests or boundary verification strong enough to
  freeze the new seam.

## Status Legend

- [ ] Not started
- [~] In progress
- [x] Complete

## Complexity Budgets

These are review targets, not immediate CI failures on day one:

- Production Kotlin file soft cap: 800 lines
- Production Rust file soft cap: 1,000 lines
- Production Kotlin function soft cap: 120 lines
- Production Rust function soft cap: 150 lines
- No new blanket production suppressions for:
  - `LongMethod`
  - `CyclomaticComplexMethod`
  - `TooManyFunctions`
  - `LongParameterList`
- New architecture exceptions must be tracked by named roadmap item or issue,
  not hidden behind file-level suppressions.

## Priority Order

1. Guardrails and authority decisions
2. Config contract unification
3. Diagnostics bounded-context split and single classifier authority
4. Native runtime/desync decomposition
5. Service and relay orchestration decomposition
6. Settings architecture split
7. Compose screen decomposition
8. ViewModel dependency shaping
9. Module/crate extraction and enforcement

## Workstream 0: Guardrails And Architecture Decisions

**Status:** [ ] Not started
**Priority:** P0
**Why now:** The codebase already contains blanket suppressions in the exact
files that need refactoring. Without budgets, ownership rules, and a migration
plan, the larger splits will stall or regress.

**Primary areas:**

- `quality:detekt-rules`
- `scripts/ci/`
- `config/detekt/`
- repo documentation

**Tasks**

- [ ] Write a short architecture ADR for each authority boundary that must end
  with a single owner:
  - canonical proxy/runtime config contract
  - canonical diagnosis engine
  - service lifecycle orchestration boundary
- [ ] Create a hotspot inventory document with the current oversized files and
  functions that this roadmap is expected to shrink.
- [ ] Add a lightweight CI/report script for:
  - largest Kotlin files
  - largest Rust files
  - files with blanket suppressions
  - files exceeding agreed review thresholds
- [ ] Add a rule or verification script preventing new blanket suppressions in
  production source unless explicitly allowlisted.
- [ ] Add a rule that new architecture work must not increase the direct module
  dependency count of `:app`, `:core:diagnostics`, or `:core:service`.
- [ ] Document the sequencing rules for architecture refactors:
  - extract interfaces first
  - move implementation second
  - remove compatibility layer last

**Done when**

- Reviewers have a shared definition of the target seams.
- New complexity suppressions cannot silently enter production code.
- Each later workstream can point to an explicit authority decision.

## Workstream 1: Config Contract Unification

**Status:** [ ] Not started
**Priority:** P0
**Why now:** The same config semantics currently exist in multiple forms across
Kotlin data models, Kotlin JSON encoding, and Rust runtime conversion. This is
the largest source-of-truth problem in the project.

**Primary areas:**

- `core/data/src/main/kotlin/com/poyka/ripdpi/data/StrategyChains.kt`
- `core/engine/src/main/kotlin/com/poyka/ripdpi/core/RipDpiProxyJsonCodec.kt`
- `native/rust/crates/ripdpi-proxy-config/src/convert.rs`
- `native/rust/crates/ripdpi-config/`

**Tasks**

- [ ] Define the canonical config authority layer and freeze its scope:
  - chain-step model
  - relay settings
  - fake-packet settings
  - QUIC settings
  - runtime context metadata
- [ ] Split `StrategyChains.kt` into focused files:
  - model enums/data classes
  - DSL formatting
  - DSL parsing
  - validation
  - protobuf mapping
  - normalization helpers
- [ ] Split `RipDpiProxyJsonCodec.kt` into section codecs:
  - listen/protocols
  - chains
  - fake packets
  - adaptive fallback
  - relay/warp/ws tunnel
  - runtime/log context
- [ ] Reduce the JSON codec object to orchestration only; nested serializable
  data classes should move into section-scoped files.
- [ ] Split Rust `runtime_config_from_ui` into section builders so the top-level
  function only validates envelope shape and delegates:
  - listen builder
  - protocol builder
  - chain builder
  - fake-packet builder
  - relay builder
  - warp builder
  - adaptive/runtime-context builder
- [ ] Isolate legacy payload compatibility into a dedicated adapter so the main
  config path is not polluted by backward-compatibility branches.
- [ ] Add round-trip tests covering:
  - AppSettings -> typed Kotlin model
  - typed Kotlin model -> JSON
  - JSON -> Rust UI payload
  - Rust UI payload -> runtime config
  - runtime config -> diagnostics/export projection where applicable
- [ ] Add golden fixtures for complex chain shapes and relay configurations.

**Improvements expected**

- A new config field should require one canonical model change and a small
  number of thin adapters, not three parallel interpretations.
- Chain parsing, formatting, and normalization should become independently
  testable.

**Done when**

- No single file owns config model, validation, JSON shape, and conversion at
  the same time.
- Contract drift between Kotlin and Rust is covered by tests instead of manual
  review.

## Workstream 2: Diagnostics Bounded-Context Split And Classifier Authority

**Status:** [ ] Not started
**Priority:** P0
**Why now:** `:core:diagnostics` currently behaves like a mini-application:
  it owns classification, workflow orchestration, recommendation application,
  exports, archive rendering, and query/persistence coordination. Diagnosis
  logic also exists on both Kotlin and Rust sides.

**Primary areas:**

- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/Models.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsServicesImpl.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsArchiveRenderer.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsScanFinalizationServices.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsFindingProjector.kt`
- `native/rust/crates/ripdpi-monitor/src/classification/diagnosis.rs`

**Tasks**

- [ ] Make an explicit decision on canonical diagnosis authority:
  - Rust classifications are authoritative and Kotlin decorates, or
  - Rust emits observations only and Kotlin classifies
- [ ] Remove dual-classification behavior from scan finalization.
- [ ] Split `:core:diagnostics` packages into clear layers:
  - `domain/`
  - `application/`
  - `finalization/`
  - `recommendation/`
  - `export/`
  - `presentation/`
  - `queries/`
- [ ] Break `DiagnosticsArchiveRenderer` into dedicated builders:
  - JSON entry builders
  - CSV entry builders
  - integrity/provenance builders
  - redaction layer
  - file attachment packers
- [ ] Extract `DefaultDiagnosticsHomeWorkflowService` from the giant services
  file into a dedicated workflow package with smaller collaborators:
  - recommendation applier
  - resolver action coordinator
  - capability evidence summarizer
  - audit outcome builder
- [ ] Split `Models.kt` by concern:
  - core scan/report contracts
  - home-audit models
  - export/share models
  - projection models
- [ ] Ensure persistence stores and artifact adapters stay at the edge, not
  inside workflow decision logic.
- [ ] Add tests proving that one classification source owns final diagnosis
  output for both stored sessions and live scans.

**Improvements expected**

- Diagnostics becomes a bounded context instead of a grab-bag utility module.
- Export and recommendation changes stop risking classifier regressions.

**Done when**

- Only one layer is responsible for final diagnosis codes.
- Archive/export code can evolve without touching workflow/finalization code.
- `DiagnosticsServicesImpl.kt` no longer acts as a catch-all service registry.

## Workstream 3: Native Runtime And Desync Decomposition

**Status:** [ ] Not started
**Priority:** P1
**Why now:** The native runtime is functionally rich but too centralized.
`desync.rs` and platform files mix tactic semantics, emitter selection, Android
fallbacks, raw-packet details, and logging in a way that blocks further
evolution.

**Primary areas:**

- `native/rust/crates/ripdpi-runtime/src/runtime/desync.rs`
- `native/rust/crates/ripdpi-runtime/src/runtime/udp.rs`
- `native/rust/crates/ripdpi-runtime/src/platform/linux.rs`
- `native/rust/crates/ripdpi-runtime/src/platform/mod.rs`

**Tasks**

- [ ] Split TCP execution into focused modules:
  - action executor
  - step-family executors
  - stream emitter
  - raw emitter
  - capability/lowering helpers
  - tracing/pcap hooks
- [ ] Move Android TTL fallback and capability degradation out of tactic-specific
  execution code into a dedicated lowering or emitter layer.
- [ ] Extract tactic-family handlers from the central executor:
  - fake/fakedsplit/fakeddisorder
  - hostfake
  - disorder/disoob/oob
  - fragmentation
  - flag override paths
- [ ] Introduce a typed per-connection capability snapshot passed into lowering
  rather than ad hoc capability checks scattered through runtime code.
- [ ] Apply the same decomposition to UDP/QUIC execution so `udp.rs` does not
  become the next monolith.
- [ ] Keep planner/runtime boundaries clear:
  - planner chooses intent
  - lowering chooses feasible emission path
  - emitter performs the send
- [ ] Add regression tests that cover the extracted step-family executors in
  isolation.

**Improvements expected**

- Adding or removing one tactic family no longer requires editing a central
  1,000-line function.
- Android-specific fallbacks become observable policy decisions, not hidden
  transport behavior.

**Done when**

- `execute_tcp_plan` and `execute_tcp_actions` stop being the main home for
  tactic-specific branching.
- Platform fallback policy is implemented once, not inside each family.

## Workstream 4: Service And Relay Orchestration Decomposition

**Status:** [ ] Not started
**Priority:** P1
**Why now:** Service runtime coordination currently relies on large base classes
and large relay-resolution switchboards. Lifecycle, handover, retries,
permission watches, telemetry loops, and runtime config shaping are too tightly
coupled.

**Primary areas:**

- `core/service/src/main/kotlin/com/poyka/ripdpi/services/ServiceRuntimeCoordinator.kt`
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/VpnServiceRuntimeCoordinator.kt`
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/ProxyServiceRuntimeCoordinator.kt`
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/UpstreamRelaySupervisor.kt`

**Tasks**

- [ ] Shrink `BaseServiceRuntimeCoordinator` into a thin shell over smaller
  collaborators:
  - lifecycle state machine
  - handover processor
  - retry/backoff policy
  - permission watchdog coordinator
  - telemetry loop coordinator
  - stop/start command executor
- [ ] Replace inheritance-driven optional behavior with composition where
  feasible, especially around handover and restart policy.
- [ ] Split `UpstreamRelaySupervisor.resolveRuntimeConfig()` into per-relay-kind
  resolvers:
  - MASQUE
  - Snowflake
  - Cloudflare Tunnel
  - Chain relay
  - ShadowTLS
  - local SOCKS/naive/TUIC/Hysteria variants as needed
- [ ] Introduce a relay resolver interface so per-kind validation, credential
  rules, and defaulting logic are not all in one function.
- [ ] Extract shared runtime start/stop logic from VPN and proxy coordinators
  only where the seam is stable; do not force false sharing.
- [ ] Add focused tests for:
  - handover restart decision logic
  - retry exhaustion
  - relay-kind validation
  - config normalization per relay kind

**Improvements expected**

- Service restarts and relay config changes become easier to reason about.
- New relay kinds stop bloating one supervisor function.

**Done when**

- Runtime lifecycle policy is split from runtime creation details.
- Relay-kind branches live behind dedicated resolvers instead of a giant
  `when` chain.

## Workstream 5: Settings Architecture Split

**Status:** [ ] Not started
**Priority:** P1
**Why now:** The settings stack has become a monolithic mutation surface. Giant
section builders and binder maps mean desync, DNS, relay, diagnostics, and
security settings all evolve through the same files.

**Primary areas:**

- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/settings/AdvancedSettingsBinder.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/settings/DesyncSection.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/dns/DnsSettingsScreen.kt`
- related advanced/settings UI models and actions

**Tasks**

- [ ] Split `AdvancedSettingsBinder` into section-scoped mutation writers:
  - connectivity/listen
  - desync/chains
  - fake-packet controls
  - QUIC
  - DNS
  - relay/warp
  - diagnostics
  - security/customization
- [ ] Replace giant global handler maps with typed section registries or
  per-section action objects.
- [ ] Split `DesyncSection.kt` into section cards with one owner each:
  - chain editor
  - adaptive split
  - TCP flags
  - hostfake/seqoverlap
  - fake ordering
  - fake payload library
  - fake TLS
  - OOB/adaptive fake TTL
- [ ] Separate visual-editor state from raw chain DSL/editor concerns so the two
  modes stop sharing one huge mutation surface.
- [ ] Split DNS settings into:
  - route/host
  - section renderers
  - section actions/mutations
  - DNS-specific validation helpers
- [ ] Add tests per settings section so refactors can move logic without using
  the full screen or full ViewModel as the test harness.

**Improvements expected**

- A change in one settings section stops touching one global mutation file.
- Visual editor and raw editor complexity can evolve independently.

**Done when**

- No single settings file owns every advanced-setting mutation path.
- Desync and DNS settings can be tested at section level.

## Workstream 6: Compose Screen Decomposition

**Status:** [ ] Not started
**Priority:** P2
**Why now:** Several screens are too large to review, test, or performance-tune
confidently. They mix route orchestration, stateful effects, transient UI state,
animations, and section rendering in the same files.

**Primary areas:**

- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/home/HomeScreen.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsScreen.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/dns/DnsSettingsScreen.kt`

**Tasks**

- [ ] Split `HomeScreen.kt` into:
  - route/host
  - banners/status area
  - connection card/button
  - analysis bottom sheets
  - telemetry/stat cards
  - helper animations where needed
- [ ] Move complex state transitions and animation orchestration into dedicated
  helpers so the composable body becomes declarative again.
- [ ] Split `DiagnosticsScreen.kt` into:
  - route/effect host
  - pager shell
  - section coordinators
  - section renderers
  - debug/performance panel
- [ ] Split `DnsSettingsScreen.kt` similarly into route shell plus section
  composables.
- [ ] Re-run Compose compiler reports after each large split to confirm the
  refactor did not widen instability or effect scope.
- [ ] Replace file-level complexity suppressions as the route files shrink.

**Improvements expected**

- Screen files become orchestration shells instead of giant render trees.
- Recomposition and effect ownership become easier to audit.

**Done when**

- Home, Diagnostics, and DNS screens are composed from section-scoped files.
- Route files primarily assemble sections and collect effects.

## Workstream 7: ViewModel Dependency Shaping And State Assembly

**Status:** [ ] Not started
**Priority:** P2
**Why now:** Some ViewModels still act as broad application shells. Large
constructor surfaces and large `combine` chains make it hard to see ownership
and to test state assembly in isolation.

**Primary areas:**

- `app/src/main/kotlin/com/poyka/ripdpi/activities/DiagnosticsViewModel.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/activities/SettingsViewModel.kt`
- patterns already present in `MainViewModel.kt`

**Tasks**

- [ ] Mirror the `MainViewModel` grouping pattern for Diagnostics and Settings:
  - grouped dependency carriers
  - action groups
  - dedicated state assemblers
- [ ] Move long `combine` pipelines out of ViewModels into assembly classes with
  narrow inputs and dedicated tests.
- [ ] Move one-time bootstrap/init work into explicit bootstrapper collaborators.
- [ ] Separate effect emission from state assembly where the two are currently
  interleaved.
- [ ] Reduce direct constructor breadth for large ViewModels without hiding too
  much unrelated behavior inside one mega-dependency bundle.
- [ ] Add unit tests for state assemblers independent of Android framework or
  Hilt wiring.

**Improvements expected**

- ViewModels become orchestration points instead of dataflow monoliths.
- State assembly logic becomes reusable and testable without UI shells.

**Done when**

- Diagnostics and Settings ViewModels follow the same dependency-shaping model
  already used successfully in `MainViewModel`.
- Constructor complexity is reduced through meaningful grouping, not by hiding
  unrelated responsibilities.

## Workstream 8: Module And Crate Extraction After Internal Cleanup

**Status:** [ ] Not started
**Priority:** P3
**Why now:** Some boundaries are module-sized, but module extraction should
follow internal decomposition, not precede it. Once authority lines are clear,
the repo can enforce them structurally.

**Candidate targets**

- Kotlin:
  - diagnostics export/archive split
  - diagnostics application/finalization split
  - optional config-contract bridge split if it stabilizes
- Rust:
  - first-flight planning/lowering shared crate if planner/runtime split settles
  - emitter/capability support crate if platform reuse emerges

**Tasks**

- [ ] Re-evaluate top-level module boundaries after Workstreams 1-7 land.
- [ ] Extract only the packages whose APIs are stable enough to enforce.
- [ ] Add dependency-boundary checks similar to the existing diagnostics
  boundary verification.
- [ ] Keep crate/module extraction minimal and justified by ownership, not by
  aesthetics.
- [ ] Document the final boundary map after extraction.

**Done when**

- At least the highest-value seams are enforced structurally rather than only by
  convention.
- New cross-boundary drift is blocked by CI or custom verification.

## Sequencing Rules

- Workstream 0 must start first.
- Workstream 1 should begin before large settings or runtime refactors because
  config authority affects both.
- Workstream 2 should settle classifier authority before diagnostics export and
  home-audit work is rearranged further.
- Workstream 3 and Workstream 4 can run in parallel once Workstream 0 is in
  place.
- Workstream 5 should start only after Workstream 1 defines the stable config
  seam for settings mutations.
- Workstream 6 should follow Workstream 5 for the settings surfaces, but Home
  and Diagnostics screen decomposition can start earlier if it stays presentational.
- Workstream 8 is intentionally last.

## Cross-Cutting Acceptance Checks

- [ ] No new production file-level complexity suppressions added.
- [ ] Every extracted seam has focused tests or a verification script.
- [ ] Major architecture moves land behind compatibility adapters first, then
  remove adapters in a follow-up.
- [ ] Documentation is updated when authority ownership changes.
- [ ] The largest current hotspots shrink measurably:
  - `desync.rs`
  - `StrategyChains.kt`
  - `RipDpiProxyJsonCodec.kt`
  - `DiagnosticsArchiveRenderer.kt`
  - `DiagnosticsServicesImpl.kt`
  - `ServiceRuntimeCoordinator.kt`
  - `UpstreamRelaySupervisor.kt`
  - `AdvancedSettingsBinder.kt`
  - `HomeScreen.kt`
  - `DiagnosticsScreen.kt`

## First Recommended Slice

Start with one narrow slice that proves the roadmap is executable:

1. Add Workstream 0 guardrails and hotspot reporting.
2. Split `StrategyChains.kt` into model/parser/formatter/validation files
   without changing behavior.
3. Split `RipDpiProxyJsonCodec.kt` into section codecs for chains and fake
   packets.
4. Add round-trip tests for one chain-heavy config.
5. Remove at least one blanket suppression from the touched files.

If that slice lands cleanly, use the same pattern for diagnostics and service
orchestration.
