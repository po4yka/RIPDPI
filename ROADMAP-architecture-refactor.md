# ROADMAP: Architecture Refactor And Modularization

> Execution roadmap derived from the 2026-04 architecture audit.
> This roadmap focuses on structural refactoring, ownership boundaries,
> modularization, and maintainability. It is separate from the completed audit
> roadmap and from the bypass-technique modernization roadmap.

## Execution Status (2026-04-18)

- **Workstream 0 (Guardrails):** COMPLETE. ADRs 001-003 committed under
  `docs/architecture/`; LoC hotspot reporter, DisallowNewSuppression detekt
  rule, Rust allow-guard with baseline, module-dep guard, and deterministic
  hotspots generator all live in `scripts/ci/` and `quality/detekt-rules/`.
- **Workstream 1 (Config Contract):** COMPLETE. `StrategyChains.kt` is split
  into focused sibling files; `RipDpiProxyJsonCodec.kt` is down to a 345-line
  orchestration layer with dedicated network/adaptive/relay/warp/runtime
  section codecs under `core/engine/.../codec/`; and
  `ripdpi-proxy-config/src/convert.rs` is down to a 147-line dispatcher on top
  of dedicated `convert/` section builders plus a legacy payload adapter.
  Cross-language round-trip harness and committed fixtures cover the canonical
  contract, and the Phase 1b completion landed in `52e4fe2c`.
- **Workstream 3 (Native Runtime And Desync Decomposition):** COMPLETE.
  The runtime now has a dedicated TCP lowering layer, a typed per-connection
  TTL/capability snapshot, a split UDP flow module, and split platform
  capability / IPv4-identification submodules on top of the earlier TCP family
  executor extraction work. `desync.rs` is still large, but the architectural
  seam this workstream was meant to create is now in place.
- **Workstream 4 (Service And Relay Orchestration):** COMPLETE.
- **Workstream 2 (Diagnostics Bounded Context):** COMPLETE. Rust diagnosis
  authority now owns final diagnoses in finalization, archive rendering is
  split under `export/`, home-audit orchestration is extracted under
  `workflow/`, diagnostics contracts are split under `model/`, and the package
  reorg now includes `application/`, `queries/`, and `recommendation/` seams.
- **Workstream 5 (Settings Architecture Split):** COMPLETE. Large settings
  routes now render through focused binder/registry and section files instead
  of one mutation-heavy surface.
- **Workstream 6 (Compose Screen Decomposition):** COMPLETE. Home,
  Diagnostics, and DNS screens now route through section-scoped files rather
  than monolithic screen implementations.
- **Workstream 7 (ViewModel Shaping):** COMPLETE. Diagnostics and Settings
  ViewModels now use grouped dependency carriers, extracted state assemblers,
  and explicit bootstrapper collaborators with focused assembler/bootstrapper
  coverage.
- **Workstream 8 (Module And Crate Extraction After Internal Cleanup):** Not
  started. See `docs/roadmap-execution-queue.md` for the unified slice list
  and dependency graph.

## Related Roadmaps

This roadmap defines the **code seams** that the rest of the active work needs to
land cleanly. It runs largely in parallel with bypass work, but certain steps
must precede specific sibling-roadmap milestones:

- [ROADMAP.md](ROADMAP.md) -- master index and cross-roadmap sequencing.
- [ROADMAP-bypass-modernization.md](ROADMAP-bypass-modernization.md) -- strategic
  bypass roadmap. It consumes the seams this roadmap creates.
  - This roadmap's Workstream 1 (config contract unification) is now complete,
    so modernization Workstream 2 should consume the shipped config seam rather
    than reopening Kotlin/Rust contract drift.
  - This roadmap's Workstream 3 (native runtime/desync decomposition) creates
    the emitter/lowering layout that modernization Workstreams 2, 3, and 7 lower
    onto. Do not migrate the IR onto the current monolith.
  - This roadmap's Workstream 4 (service and relay orchestration) overlaps with
    modernization Workstream 8 (Android hardening); coordinate on VPN lifecycle
    and handover events.
- [ROADMAP-bypass-techniques.md](ROADMAP-bypass-techniques.md) -- tactical bypass
  checklist. Tier 3 items (SYN-Hide, UDP-over-ICMP) extend `ripdpi-root-helper`
  and `ripdpi-runtime`; schedule them after this roadmap's Workstream 3 so new
  step-family executors land on the decomposed layout, not on today's
  `execute_tcp_plan`.
- [docs/roadmap-integrations.md](docs/roadmap-integrations.md) -- transport
  operations. This roadmap's Workstream 4 (service/relay orchestration cleanup)
  refactors the relay resolvers that the integrations roadmap drives field
  validation for; align relay-kind interface changes with the transports it
  tracks (MASQUE, Cloudflare Tunnel, Finalmask, NaiveProxy).

### Ownership Notes

This roadmap owns the **code structure**; the sibling roadmaps own the feature
semantics that live inside it.

| Topic | This roadmap owns | Sibling roadmap owns |
|-------|-------------------|---------------------|
| Config contract shape | Workstream 1 (file split, round-trip tests) | bypass roadmaps consume the canonical contract |
| Diagnostics classifier authority | Workstream 2 (single-source decision) | modernization Workstream 4 defines DNS oracle outputs |
| Runtime/desync file layout | Workstream 3 | modernization Workstream 2 (IR) and techniques (step kinds) |
| Service lifecycle | Workstream 4 | modernization Workstream 8 (Android hardening events) |
| Relay resolver interface | Workstream 4 | integrations (per-transport field validation) |
| Settings surface split | Workstream 5 | bypass roadmaps contribute the fields that sections render |

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

- Production Kotlin file soft cap: 800 lines (1,000 for Compose screens)
- Production Rust file soft cap: 1,000 lines (1,500 for platform / runtime hubs)
- Production Kotlin function soft cap: 120 lines
- Production Rust function soft cap: 150 lines
- No new blanket production suppressions for:
  - `LongMethod`
  - `CyclomaticComplexMethod`
  - `TooManyFunctions`
  - `LongParameterList`
- New architecture exceptions must be tracked by named roadmap item or issue,
  not hidden behind file-level suppressions.

## Existing Guardrail Infrastructure

The repo already ships the following CI pieces. Workstream 0 extended this
baseline rather than replacing it:

- `scripts/ci/check_file_loc_limits.py` (456 lines) -- code-only LoC enforcement
  (strips comments/blanks), fails on new violations against the baseline.
- `config/static/file-loc-baseline.json` -- 15 tracked hotspot entries with
  per-file code-only LoC snapshots (Kotlin 700 / Compose 1000 / Rust 1500).
- `scripts/ci/verify_native_sizes.py` (257 lines) + `scripts/ci/native-size-baseline.json`
  -- .so size guard per ABI for `libripdpi.so` and `libripdpi-tunnel.so`.
- `scripts/ci/verify_diagnostics_boundary.py` -- enforces that `:core:diagnostics`
  does not depend on `:core:service`.
- `quality/detekt-rules/.../DisallowNewSuppression.kt` -- rejects new
  production Kotlin suppressions outside the checked-in allowlist.
- `scripts/ci/check_rust_allows.py` + baseline -- rejects new production Rust
  `#[allow]` usage outside the grandfathered set.
- `scripts/ci/verify_module_deps.py` -- prevents direct `project(...)`
  dependency growth for `:app`, `:core:diagnostics`, and `:core:service`.
- `docs/architecture/adr-001-config-contract.md`,
  `adr-002-diagnosis-classifier.md`, `adr-003-service-lifecycle.md` --
  authority decisions for the core ownership seams.
- `docs/architecture/hotspots.md` + `scripts/ci/generate_hotspots_doc.py` --
  deterministic hotspot inventory generated from the live repo state.

## Hotspot Inventory (2026-04-15 snapshot)

Code-only line counts (blanks and comments stripped). Suppression counts reflect
`@Suppress` (Kotlin) or `#[allow]` (Rust) annotations in each file.

### Kotlin (Workstream 1, 2, 4, 5, 6, 7)

| File | Raw / Code-only | Budget | Delta | Suppressions | Owner Workstream |
|------|-----------------|--------|-------|-------|------------------|
| `StrategyChains.kt` | 1305 / 822 | 800 | **+22** | 5 | W1 |
| `RipDpiProxyJsonCodec.kt` | 1494 / 708 | 800 | -92 | 0 | W1 |
| `DiagnosticsArchiveRenderer.kt` | 1474 / **1188** | 800 | **+388 (+48%)** | 0 | W2 |
| `Models.kt` (diagnostics) | 872 / 769 | 800 | -31 | 1 | W2 |
| `DiagnosticsServicesImpl.kt` | 741 / - | 800 | -59 | 0 | W2 |
| `ServiceRuntimeCoordinator.kt` | 383 / - | 800 | -417 | 2 | W4 |
| `VpnServiceRuntimeCoordinator.kt` | 542 / - | 800 | -258 | 2 | W4 |
| `ProxyServiceRuntimeCoordinator.kt` | 309 / - | 800 | -491 | 0 | W4 |
| `UpstreamRelaySupervisor.kt` | 469 / - | 800 | -331 | 2 | W4 |
| `AdvancedSettingsBinder.kt` | 1262 / 781 | 800 | -19 | 1 | W5 |
| `DesyncSection.kt` | 845 / - | 800 | **+45** | 1 | W5 |
| `HomeScreen.kt` | 1945 / 324 | 1000 | -676 | 1 | W6 |
| `DiagnosticsScreen.kt` | 1364 / 623 | 1000 | -377 | 1 | W6 |
| `DnsSettingsScreen.kt` | 1518 / 707 | 1000 | -293 | 1 | W6 |
| `DiagnosticsViewModel.kt` | 464 / - | 800 | -336 | 1 | W7 |
| `SettingsViewModel.kt` | 298 / - | 800 | -502 | 2 | W7 |
| `MainViewModel.kt` (reference) | 499 / - | 800 | -301 | 0 | - |

### Rust (Workstream 3, 2026-04-17 live snapshot)

| File | Raw LoC | Budget | Current note |
|------|--------:|-------:|--------------|
| `runtime/desync.rs` | **4622** | 1500 | executor + lowering seams are split, but this file still owns too much orchestration and strategy glue |
| `runtime/udp.rs` | 499 | 1000 | flow policy moved into `runtime/udp/flow.rs`; main file is now orchestration-first |
| `platform/linux.rs` | **1813** | 1500 | fake-send and fragmentation helpers are split; remaining size is mostly Linux socket substrate |
| `platform/mod.rs` | 606 | 1000 | capability and IPv4-id helpers moved to dedicated submodules |
| `ripdpi-proxy-config/src/convert.rs` | 147 | 1000 | now an orchestration-only dispatcher; section builders live in `src/convert/` |

### Critical Blocker

**Workstream 3 is no longer the blocker.** `execute_tcp_plan` already runs as a smaller dispatcher/control loop, and the remaining architectural tail is now landed: Android TTL degradation moved into `runtime/tcp_lowering.rs`, UDP flow policy moved into `runtime/udp/flow.rs`, and platform capability / IPv4-id helpers moved out of `platform/mod.rs`. Remaining modernization blockers now sit in Workstream 2's planner-wide IR migration rather than the runtime split itself.

### Suppression Totals

- Kotlin: **35 `@Suppress`** across hotspots (19 concentrated in the three Compose screens).
- Rust: **30 `#[allow]`** across hotspots (15 in `platform/mod.rs` alone).

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

**Status:** [x] Complete (2026-04-15)
**Priority:** P0
**Why first:** Later workstreams needed explicit ownership seams and CI guards
before more structural refactors could land safely.

**Primary areas:**

- `quality:detekt-rules`
- `scripts/ci/check_file_loc_limits.py`
- `scripts/ci/check_rust_allows.py`
- `scripts/ci/verify_module_deps.py`
- `config/detekt/`
- `config/static/file-loc-baseline.json`
- `docs/architecture/`

**Completed scope**

- [x] Wrote a short architecture ADR for each authority boundary that must end
  with a single owner. Commit as `docs/architecture/adr-00N-*.md`:
  - canonical proxy/runtime config contract (Kotlin `StrategyChains.kt` vs Rust
    `convert.rs` vs JSON codec)
  - canonical diagnosis engine (Rust `ripdpi-monitor/src/classification/diagnosis.rs`
    vs Kotlin `DiagnosticsServicesImpl` + `DiagnosticsFindingProjector`)
  - service lifecycle orchestration boundary (`BaseServiceRuntimeCoordinator`
    and its VPN/Proxy subclasses)
- [x] Embedded the Hotspot Inventory table (above) as `docs/architecture/hotspots.md`
  and wire a generator script so the table stays current.
- [x] Extended `check_file_loc_limits.py` with a reporting mode that also emits:
  - top-5 longest functions per hotspot file
  - suppression counts per file
- [x] Added a detekt custom rule (`quality:detekt-rules`) rejecting new
  `@Suppress(...)` / `@file:Suppress(...)` in production Kotlin unless a
  grandfathered allowlist entry references this roadmap's item number.
- [x] Added a Rust allow-guard rejecting new `#[allow(...)]` in production Rust
  unless tied to a baseline entry.
- [x] Added a module-dependency guard modeled on `verify_diagnostics_boundary.py`
  that fails CI if the direct `project(...)` count for `:app`,
  `:core:diagnostics`, or `:core:service` grows beyond snapshotted baseline.
- [x] Documented the sequencing rules for architecture refactors:
  - extract interfaces first
  - move implementation second
  - remove compatibility layer last

**Residual follow-up**

- Refresh the hotspot doc and guard baselines when intentional refactors move
  the budgeted files or suppression inventory.
- Keep any new architecture exception tied to a named roadmap item or issue
  instead of growing the allowlists silently.

## Workstream 1: Config Contract Unification

**Status:** [x] Complete (2026-04-17)
**Priority:** P0
**Why now:** The same config semantics currently exist in multiple forms across
Kotlin data models, Kotlin JSON encoding, and Rust runtime conversion. This is
the largest source-of-truth problem in the project.

**Primary areas:**

- `core/data/src/main/kotlin/com/poyka/ripdpi/data/StrategyChain*.kt`
- `core/engine/src/main/kotlin/com/poyka/ripdpi/core/RipDpiProxyJsonCodec.kt`
- `core/engine/src/main/kotlin/com/poyka/ripdpi/core/codec/`
- `native/rust/crates/ripdpi-proxy-config/src/convert.rs`
- `native/rust/crates/ripdpi-proxy-config/src/convert/`
- `native/rust/crates/ripdpi-config/`

**Completed scope**

- [x] Defined the canonical config authority layer and froze the slice boundary
  for chain-step semantics, fake-packet settings, relay-heavy fixtures, and
  the current JSON round-trip harness.
- [x] Split the former `StrategyChains.kt` monolith into focused files:
  - model enums/data classes
  - DSL formatting
  - DSL parsing
  - validation
  - protobuf mapping
  - default chain definitions
- [x] Split `RipDpiProxyJsonCodec.kt` into section codecs for:
  - chains
  - fake packets
- [x] Split `RipDpiProxyJsonCodec.kt` remaining sections into dedicated codecs
  for:
  - listen / protocols / QUIC / hosts / host-autolearn
  - adaptive fallback
  - relay
  - warp / ws-tunnel
  - runtime / log / session context
- [x] Reduced the parent JSON codec toward orchestration-only ownership for the
  full config surface; the parent now owns encode/decode orchestration, payload
  shape validation, and runtime-context rewrite/strip flows only.
- [x] Added round-trip tests covering:
  - AppSettings -> typed Kotlin model
  - typed Kotlin model -> JSON
  - JSON -> Rust UI payload
  - Rust UI payload -> runtime config
- [x] Added committed fixtures for chain-heavy, UDP/QUIC-heavy, and relay-heavy
  configurations.
- [x] Removed one blanket `@file:Suppress(TooManyFunctions)` from the split
  Kotlin config surface.
- [x] Split Rust `runtime_config_from_ui` into section builders so the top-level
  function only validates envelope shape and delegates:
  - listen builder
  - protocol builder
  - chain builder
  - fake-packet builder
  - relay builder
  - warp builder
  - adaptive/runtime-context builder
- [x] Isolated legacy payload compatibility into a dedicated adapter so the
  main config path is not polluted by backward-compatibility branches.
- [x] Extended round-trip coverage with the completed Kotlin/Rust split so
  runtime-context, warp, ws-tunnel, and log-context normalization are pinned by
  focused tests in addition to the existing heavy fixtures.

**Residual follow-up**

- Keep new config fields routed through the section codecs / section builders
  instead of regrowing cross-language inline mapping logic in the parent files.
- If runtime-context export projection grows further, keep it as a new focused
  codec or adapter rather than widening `RipDpiProxyJsonCodec.kt` again.

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

**Status:** [x] Complete (2026-04-17)
**Priority:** P0
**Why now:** `:core:diagnostics` currently behaves like a mini-application:
  it owns classification, workflow orchestration, recommendation application,
  exports, archive rendering, and query/persistence coordination. Diagnosis
  logic also exists on both Kotlin and Rust sides.

**Primary areas:**

- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/Models.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/model/`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsServicesImpl.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsArchiveRenderer.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/finalization/`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/queries/`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/recommendation/`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/workflow/`
- `native/rust/crates/ripdpi-monitor/src/classification/diagnosis.rs`

**Completed scope**

- [x] Locked canonical diagnosis authority to the Rust engine per ADR 0.1 and
  removed Kotlin-side dual classification from scan finalization.
- [x] Added a focused `finalization/` seam so final-diagnosis ownership is
  explicit instead of being buried in a giant service file.
- [x] Completed the package reorg for the active diagnostics seams:
  - `application/`
  - `export/`
  - `finalization/`
  - `model/`
  - `queries/`
  - `recommendation/`
  - existing `domain/` / `presentation/`
- [x] Split archive rendering into dedicated entry builders under `export/`:
  - JSON entry builder
  - CSV entry builder
  - shared integrity / provenance helpers
- [x] Extracted `DefaultDiagnosticsHomeWorkflowService` into `workflow/` with
  smaller collaborators:
  - recommendation applier
  - resolver action coordinator
  - capability evidence summarizer
  - audit outcome builder
- [x] Split the old diagnostics model monolith into `model/` files for:
  - core scan/report contracts
  - context/runtime models
  - export/share models
  - projection-facing models
- [x] Reduced `Models.kt` to compatibility helpers instead of a shared dumping
  ground, and reduced `DiagnosticsServicesImpl.kt` to the remaining bootstrap /
  share / detail / resolver service ownership.
- [x] Kept persistence stores and artifact adapters at the workflow edge rather
  than inside diagnosis-authority decisions.
- [x] Added regression coverage proving engine-authored diagnoses and
  classifier-version metadata survive finalization instead of being rewritten by
  Kotlin-side projection logic.

**Residual follow-up**

- If diagnostics growth continues, prefer further narrowing of the shipped
  `export/`, `finalization/`, `workflow/`, and `model/` seams over a
  repo-wide package-rename exercise.
- Keep new archive/export behavior out of workflow/finalization files; the
  parent renderer should remain an orchestrator rather than regrowing helpers.
- If recommendation logic grows materially, extract an explicit
  `recommendation/` package rather than widening `workflow/` again.

**Improvements expected**

- Diagnostics becomes a bounded context instead of a grab-bag utility module.
- Export and recommendation changes stop risking classifier regressions.

**Done when**

- Only one layer is responsible for final diagnosis codes.
- Archive/export code can evolve without touching workflow/finalization code.
- `DiagnosticsServicesImpl.kt` no longer acts as a catch-all service registry.

## Workstream 3: Native Runtime And Desync Decomposition

**Status:** [x] Complete (2026-04-17)
**Priority:** P1
**Why now:** The native runtime is functionally rich but too centralized.
`desync.rs` and platform files mix tactic semantics, emitter selection, Android
fallbacks, raw-packet details, and logging in a way that blocks further
evolution. This is also the single largest blocker for bypass-modernization
Workstreams 2 (first-flight IR) and 3 (QUIC subsystem).

**Completed state (2026-04-17):**

- `native/rust/crates/ripdpi-runtime/src/runtime/desync.rs` is still the main
  runtime hotspot at 4622 raw lines, but the old central TCP plan switch has
  already been broken apart:
  - `execute_tcp_plan` now spans line 2284 through 2412 and acts mainly as a
    dispatcher/control loop.
  - `execute_tcp_plan_step` and `handle_tcp_plan_step_control` own per-step
    routing and control flow.
  - dedicated helpers exist for the basic stream path, TTL-sensitive families,
    fake families, hostfake, `IpFrag2`, `FakeRst`, and grouped
    `MultiDisorder` preparation.
- `native/rust/crates/ripdpi-runtime/src/runtime/udp.rs` now owns only the
  association loop and response path; flow policy moved to
  `runtime/udp/flow.rs` (309 lines).
- `native/rust/crates/ripdpi-runtime/src/platform/linux.rs` now sits at 1813
  raw lines with fake-send and fragmentation helpers already split into
  dedicated Linux submodules.
- `native/rust/crates/ripdpi-runtime/src/platform/mod.rs` is down to 606 raw
  lines after capability and IPv4-id helpers moved into dedicated submodules.

**Shipped so far**

- [x] Extracted dedicated TCP family executors for:
  - basic stream / low-state families
  - TTL-sensitive families
  - fake / fakedsplit / fakeddisorder
  - hostfake
  - `IpFrag2`
  - `FakeRst`
- [x] Extracted per-step dispatcher/control helpers so `execute_tcp_plan` is
  no longer the main home for tactic-family branching.
- [x] Split grouped `MultiDisorder` preparation from send execution.
- [x] Moved Android TTL fallback and capability degradation into
  `runtime/tcp_lowering.rs`.
- [x] Introduced a typed per-connection lowering capability snapshot passed
  through TCP execution.
- [x] Split UDP flow policy into `runtime/udp/flow.rs`.
- [x] Split platform capability and IPv4-id allocation helpers into
  `platform/capabilities.rs` and `platform/ipv4_ids.rs`.
- [x] Extended runtime regressions so the extracted lowering and flow seams
  have direct unit coverage.

**Tasks**

- [x] Split TCP execution into focused modules:
  - [x] step-family executors
  - [x] dispatcher/control helpers
  - [x] stream emitter / raw emitter boundary cleanup
  - [x] capability/lowering helpers
  - [x] tracing/pcap hook cleanup to the new module boundaries
- [x] Move Android TTL fallback and capability degradation out of tactic-specific
  execution code into a dedicated lowering or emitter layer.
- [x] Extract tactic-family handlers from the central executor:
  - fake/fakedsplit/fakeddisorder
  - hostfake
  - disorder/disoob/oob/basic stream
  - `IpFrag2` / `FakeRst` tail paths
- [x] Finish the remaining fragmentation and flag-override lowering paths so
  raw-send policy is no longer spread across executor branches.
- [x] Introduce a typed per-connection capability snapshot passed into lowering
  rather than ad hoc capability checks scattered through runtime code.
- [x] Apply the same decomposition to UDP/QUIC execution so `udp.rs` does not
  become the next monolith.
- [x] Split `platform/linux.rs` raw-send helpers and `platform/mod.rs`
  capability/fallback dispatch along the new lowering boundary.
- [x] Keep planner/runtime boundaries clear:
  - planner chooses intent
  - lowering chooses feasible emission path
  - emitter performs the send
- [x] Add regression tests that cover the extracted step-family executors in
  isolation; keep extending coverage as the remaining lowering pieces move.

**Improvements expected**

- Adding or removing one tactic family no longer requires editing a central
  1,000-line function.
- Android-specific fallbacks become observable policy decisions, not hidden
  transport behavior.

**Done when**

- [x] `execute_tcp_plan` and `execute_tcp_actions` are no longer the main home
  for tactic-specific branching or capability fallbacks.
- [x] Platform fallback policy is implemented once, not inside each family.

## Workstream 4: Service And Relay Orchestration Decomposition

**Status:** [x] Complete
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

- [x] Shrink `BaseServiceRuntimeCoordinator` into a thin shell over smaller
  collaborators:
  - lifecycle state machine
  - handover processor
  - retry/backoff policy
  - permission watchdog coordinator
  - telemetry loop coordinator
  - stop/start command executor
- [x] Replace inheritance-driven optional behavior with composition where
  feasible, especially around handover and restart policy.
- [x] Split relay runtime-config resolution into per-relay-kind
  resolvers:
  - MASQUE
  - Snowflake
  - Cloudflare Tunnel
  - Chain relay
  - ShadowTLS
  - local SOCKS/naive/TUIC/Hysteria variants as needed
- [x] Introduce a relay resolver interface so per-kind validation, credential
  rules, and defaulting logic are not all in one function.
- [x] Keep `UpstreamRelaySupervisor` on the same shared default resolver
  composition in both constructor paths so runtime config assembly is no longer
  duplicated.
- [x] Extract shared runtime start/stop logic from VPN and proxy coordinators
  only where the seam is stable; do not force false sharing.
- [x] Add focused tests for:
  - handover restart decision logic
  - retry exhaustion
  - relay-kind validation
  - config normalization per relay kind

**Current state**

- `DefaultUpstreamRelayRuntimeConfigResolver` now dispatches through dedicated
  relay-kind resolvers for MASQUE, Cloudflare Tunnel, Snowflake, Chain relay,
  ShadowTLS, NaiveProxy, local-path transports, and default relay families.
- Credential checks, feature gates, and runtime defaults are split into focused
  helpers instead of one mixed `when` chain.
- `BaseServiceRuntimeCoordinator` now owns smaller collaborators for lifecycle
  start/stop, handover monitoring and exponential retry, permission-watch
  collection, and telemetry-loop ownership instead of managing those concerns
  inline.
- VPN and proxy coordinators now share the stable relay/warp/proxy runtime
  seam through `SharedProxyRuntimeStack` while keeping VPN-only tunnel behavior
  outside the shared helper.
- Focused unit coverage now exercises relay-kind normalization/validation plus
  captive-portal handover suppression and retry exhaustion behavior.

**Improvements expected**

- Service restarts and relay config changes become easier to reason about.
- New relay kinds stop bloating one supervisor function.

**Done when**

- Runtime lifecycle policy is split from runtime creation details.
- Relay-kind branches live behind dedicated resolvers instead of a giant
  `when` chain.

## Workstream 5: Settings Architecture Split

**Status:** [x] Complete
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

- [x] Split `AdvancedSettingsBinder` into section-scoped mutation writers:
  - connectivity/listen
  - desync/chains
  - fake-packet controls
  - QUIC
  - DNS
  - relay/warp
  - diagnostics
  - security/customization
- [x] Replace giant global handler maps with typed section registries or
  per-section action objects.
- [x] Split `DesyncSection.kt` into section cards with one owner each:
  - chain editor
  - adaptive split
  - TCP flags
  - hostfake/seqoverlap
  - fake ordering
  - fake payload library
  - fake TLS
  - OOB/adaptive fake TTL
- [x] Separate visual-editor state from raw chain DSL/editor concerns so the two
  modes stop sharing one huge mutation surface.
- [x] Split DNS settings into:
  - route/host
  - section renderers
  - section actions/mutations
  - DNS-specific validation helpers
- [x] Add tests per settings section so refactors can move logic without using
  the full screen or full ViewModel as the test harness.

**Improvements expected**

- A change in one settings section stops touching one global mutation file.
- Visual editor and raw editor complexity can evolve independently.

**Done when**

- No single settings file owns every advanced-setting mutation path.
- Desync and DNS settings render through section-scoped files and binder
  registries, with focused section/helper coverage in the app unit suite.

## Workstream 6: Compose Screen Decomposition

**Status:** [x] Complete
**Priority:** P2
**Why now:** Several screens are too large to review, test, or performance-tune
confidently. They mix route orchestration, stateful effects, transient UI state,
animations, and section rendering in the same files.

**Primary areas:**

- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/home/HomeScreen.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsScreen.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/dns/DnsSettingsScreen.kt`

**Tasks**

- [x] Split `HomeScreen.kt` into:
  - route/host
  - banners/status area
  - connection card/button
  - analysis bottom sheets
  - telemetry/stat cards
  - helper animations where needed
- [x] Move complex state transitions and animation orchestration into dedicated
  helpers so the composable body becomes declarative again.
- [x] Split `DiagnosticsScreen.kt` into:
  - route/effect host
  - pager shell
  - section coordinators
  - section renderers
  - debug/performance panel
- [x] Split `DnsSettingsScreen.kt` similarly into route shell plus section
  composables.
- [x] Re-run Compose compiler reports after each large split to confirm the
  refactor did not widen instability or effect scope.
- [x] Replace file-level complexity suppressions as the route files shrink.

**Improvements expected**

- Screen files become orchestration shells instead of giant render trees.
- Recomposition and effect ownership become easier to audit.

**Done when**

- Home, Diagnostics, and DNS screens are composed from route/section files.
- Release-side Compose reports were regenerated into
  `app/build/compose-reports/` and `app/build/compose-metrics/release/`.
- Route files primarily assemble sections and collect effects.

## Workstream 7: ViewModel Dependency Shaping And State Assembly

**Status:** [x] Complete (2026-04-18)
**Priority:** P2
**Why now:** Some ViewModels still act as broad application shells. Large
constructor surfaces and large `combine` chains make it hard to see ownership
and to test state assembly in isolation.

**Primary areas:**

- `app/src/main/kotlin/com/poyka/ripdpi/activities/DiagnosticsViewModel.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/activities/SettingsViewModel.kt`
- patterns already present in `MainViewModel.kt`

**Completed scope**

- [x] Mirrored the `MainViewModel` grouping pattern for Diagnostics and Settings
  through focused dependency carriers instead of long raw constructor lists.
- [x] Moved the long `combine` pipelines out of `DiagnosticsViewModel` and
  `SettingsViewModel` into dedicated assembly collaborators:
  - `DiagnosticsUiStateAssembler`
  - `SettingsUiStateAssembler`
- [x] Moved one-time bootstrap/init work into explicit collaborators:
  - `DiagnosticsViewModelBootstrapper`
  - `SettingsViewModelBootstrapper`
- [x] Kept state assembly separate from action/effect paths so the ViewModels
  now act as orchestration shells rather than dataflow owners.
- [x] Reduced constructor breadth without hiding unrelated behavior inside a
  single mega-dependency bundle.
- [x] Added focused unit coverage for the extracted assembly path:
  - `DiagnosticsUiStateAssemblerTest`
  - `SettingsUiStateAssemblerTest`
  - `DiagnosticsViewModelBootstrapperTest`
  - `SettingsViewModelBootstrapperTest`
  - existing `DiagnosticsViewModelTest` coverage remains the higher-level
    behavioral guard.

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
