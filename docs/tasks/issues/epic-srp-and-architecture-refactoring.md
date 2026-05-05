---
title: Epic - SRP and architecture refactoring across Kotlin and Rust layers
type: epic
status: backlog
area: epic
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
child_count: 38
---

- [ ] #task Epic - SRP and architecture refactoring across Kotlin and Rust layers #repo/RIPDPI #area/epic #status/backlog ⏫

## Goal

Eliminate god objects and responsibility violations identified by the kotlin-design-auditor and arch-layer-auditor across the Kotlin service layer, Kotlin UI layer, and Rust native crates. Each file or struct in scope should own exactly one axis of change after the refactor.

## Why now

Three concurrent audits (kotlin-design-auditor, arch-layer-auditor, rust-api-auditor) surfaced 38 hotspots across the Kotlin UI/service layers and Rust native crates. Each hotspot is a serial bottleneck: every feature touch, bug fix, or policy change must navigate the full surface even when only a small slice is relevant. Addressing them together reduces review burden and makes future protocol and UI work parallelisable across the team.

## Key decisions

- **Split by responsibility axis, not by arbitrary LOC.** The target is one reason to change per file, not a line-count ceiling.
- **Preserve behavior throughout.** Every split is a pure refactor; no logic changes are bundled. Existing tests must stay green after each child task.
- **Facades over flag days.** Where a public type is widely referenced, introduce a thin delegating facade so call sites do not need mass updates.
- **Rust crate boundary changes follow the existing layering rules.** New traits go into API crates; composition wiring goes into composition or integration crates; no circular deps introduced.

## Scope

**In scope — Kotlin service layer (P2)**
- `VpnServiceRuntimeCoordinator` — split into tunnel, proxy-stack, DNS-policy, telemetry coordinators
- `DefaultConnectionPolicyResolver` — extract DNS selection, context assembly, policy matching, signature builder
- `OwnedStackBrowserService` — separate contracts, ECH evidence, platform/native fetchers, response decoder
- `SettingsUiModels.kt` — split into per-feature state packages

**In scope — Kotlin UI layer (P2/P3)**
- `SettingsUiModels.kt` — per-feature state packages (see above)
- `AdvancedSettingsScreen` — feature-scoped setting modules + registry
- `DiagnosticsUiStrategySupport` — 5 focused presentation mappers
- `DetectionCheckScreen` — 6 focused composables
- `HomeAnalysisPanels` — 5 panel composables, remove suppressed detekt violations
- `RipDpiState.kt` — per-component-family token files behind `RipDpiThemeTokens` facade

**In scope — Kotlin service layer (P3)**
- `UpstreamRelaySupervisorSupport` — merge, validation, resolution, credential modules

**In scope — Rust native (P3)**
- `ripdpi-monitor-engine` — introduce lane trait + composition crate
- `local-network-fixture` — per-protocol builders + manifest assembler
- `ripdpi-cloudflare-origin/main.rs` — 6 modules, main.rs reduced to wiring

**In scope — Kotlin ViewModel layer (audit batch 2, P2)**
- `ConfigViewModel` — split draft editing, relay credential hydration/persistence, capability observation
- `DetectionCheckViewModel` — fix `AndroidViewModel` misuse, extract HTTP and persistence concerns
- `OnboardingViewModel` — extract `OnboardingPermissionCoordinator`
- `DetectionHistoryStore` — extract interface, add `Dispatchers.IO` wrapper
- `DiagnosticsUiModels.kt` — extract business-derivation extension properties into mappers

**In scope — Kotlin UI layer (audit batch 2, P3)**
- `LogsViewModel` — extract `LogEntryMapper` and `LogAggregatorUseCase`; fix `SimpleDateFormat`
- `DesyncSection.kt` — split 843-LOC monolithic `LazyListScope` into 6 focused sections
- `RelayFields.kt` — split 746-LOC file into per-relay-kind composables
- 3 `LaunchedEffect(Unit)` wrong-key sites in route composables
- `CommunityComparisonStore` — add `@Inject`/`@Singleton`, remove dual ad-hoc instantiation

**In scope — Rust API surface (audit batch 2, P2)**
- `ResolvedRelayRuntimeConfig` 52-field god struct → per-variant enum payload
- `RelayRuntime` 5-`Mutex` hot path → `OnceLock` + `ArcSwap`
- `AdaptivePort` 27-method ISP violation → 3 focused traits
- `PolicyPort` — extract `DirectPathLearningPort`

**In scope — Rust API surface (audit batch 2, P3)**
- `ripdpi-diagnostics-contracts` glob re-exports in 9 crates → selective `pub use`
- `ServicesStateHandle` `pub` inner field → `pub(crate)`
- Dead `retry_stealth` field in `ServicesState`
- `adaptive_port_impl.rs` 415-LOC mechanical delegator (derives from AdaptivePort split)

**In scope — Rust / Kotlin arch layer (audit batch 2, P2)**
- `ripdpi-runtime-adaptive` → `ripdpi-runtime-strategy` direction violation
- `ripdpi-runtime-adaptive` wildcard re-export of `ripdpi-runtime-policy` internals
- `ripdpi-android-platform-adapter` → `ripdpi-runtime-strategy` JNI boundary violation

**In scope — Rust / Kotlin arch layer (audit batch 2, P3)**
- `ripdpi-runtime-learning` orphan wiring crate — document or remove
- `ripdpi-diagnostics-telegram` → `ripdpi-ws-bootstrap`/`ripdpi-ws-tunnel` Protocol coupling
- `ripdpi-diagnostics-probes` zero-logic facade duplicating `ripdpi-diagnostics-runner`
- `ripdpi-failure-classifier` raw path dep → workspace dep
- `:core:service` `testImplementation` → `:core:detection` layer direction violation

**Out of scope:**
- Behavior changes, new features, or performance optimizations
- Detekt baseline extensions (violations must be fixed, not suppressed)
- Any file not named above

## Ship definition

- [ ] All 38 child tasks reach `done`.
- [ ] No detekt baseline entries added; existing suppressed violations in `HomeAnalysisPanels` and `DesyncSection` removed.
- [ ] `ripdpi-monitor-engine/Cargo.toml` lists no concrete lane crates.
- [ ] `ripdpi-runtime-adaptive/Cargo.toml` has no `ripdpi-runtime-strategy` dep.
- [ ] `ripdpi-android-platform-adapter/Cargo.toml` has no `ripdpi-runtime-strategy` dep.
- [ ] Zero `pub use ripdpi_diagnostics_contracts::*` glob re-exports in the workspace.
- [ ] `cargo deny check` passes with the new Rust dependency graph.
- [ ] All Roborazzi goldens pass (no visual regression).
- [ ] `cargo nextest run` and Android instrumentation tests green on CI.
- [ ] Zero `LaunchedEffect(Unit)` calls collecting ViewModel SharedFlows.
- [ ] `@Binds` used for `RelayCredentialRepository` and `DetectionHistoryRepository` in Hilt modules.

## Child tasks

**P2 — Kotlin service layer (batch 1)**
- [[Split VpnServiceRuntimeCoordinator into focused coordinators]]
- [[Split DefaultConnectionPolicyResolver into separate policy services]]
- [[Split OwnedStackBrowserService into transport-layer components]]
- [[Split SettingsUiModels into per-feature state packages]]

**P2 — Kotlin ViewModel layer (batch 2)**
- [[Split ConfigViewModel into draft, credential, and capability concerns]]
- [[Split DetectionCheckViewModel and fix AndroidViewModel misuse]]
- [[Extract OnboardingPermissionCoordinator from OnboardingViewModel]]
- [[Extract DetectionHistoryRepository interface and fix synchronous IO in DetectionHistoryStore]]
- [[Split DiagnosticsUiModels.kt — extract business derivation into mappers]]

**P3 — Kotlin UI layer (batch 1)**
- [[Move AdvancedSettingsScreen taxonomy into feature-specific settings modules]]
- [[Split DiagnosticsUiStrategySupport into focused presentation mappers]]
- [[Split DetectionCheckScreen into focused composable components]]
- [[Split HomeAnalysisPanels into single-responsibility panel composables]]
- [[Split RipDpiState theme tokens by component family]]

**P3 — Kotlin UI layer (batch 2)**
- [[Extract LogEntryMapper and LogAggregatorUseCase from LogsViewModel]]
- [[Split DesyncSection.kt monolithic LazyListScope into per-feature sections]]
- [[Split RelayFields.kt into per-relay-kind composable files]]
- [[Fix LaunchedEffect(Unit) wrong key in 3 composable routes]]
- [[Inject CommunityComparisonStore as @Singleton via Hilt]]

**P3 — Kotlin service layer (batch 1)**
- [[Split UpstreamRelaySupervisorSupport into merge, validation, and resolution modules]]

**P2 — Rust API surface (batch 2)**
- [[Replace ResolvedRelayRuntimeConfig god struct with a per-variant enum payload]]
- [[Replace RelayRuntime Mutex fields with OnceLock and ArcSwap]]
- [[Split AdaptivePort into AdaptiveHintPort, AdaptiveFeedbackPort, and RetryPacingPort]]
- [[Extract DirectPathLearningPort from PolicyPort]]

**P3 — Rust API surface (batch 2)**
- [[Replace glob re-exports of ripdpi-diagnostics-contracts in 9 diagnostics crates]]
- [[Restrict ServicesStateHandle inner Arc field to pub(crate)]]
- [[Remove dead ServicesState fields and cap RwLock field growth]]

**P2 — Rust / Kotlin arch layer (batch 2)**
- [[Decouple ripdpi-runtime-adaptive from ripdpi-runtime-strategy]]
- [[Remove wildcard re-export of ripdpi-runtime-policy from ripdpi-runtime-adaptive]]
- [[Remove ripdpi-runtime-strategy direct dep from ripdpi-android-platform-adapter]]

**P3 — Rust / Kotlin arch layer (batch 2)**
- [[Document or remove ripdpi-runtime-learning orphan wiring crate]]
- [[Decouple ripdpi-diagnostics-telegram from Protocol-layer ws-bootstrap and ws-tunnel]]
- [[Evaluate and remove ripdpi-diagnostics-probes zero-logic facade crate]]
- [[Replace ripdpi-failure-classifier path dependency with workspace dependency]]
- [[Remove :core:service test dependency on :core:detection (layer direction violation)]]

**P3 — Rust native (batch 1)**
- [[Decouple ripdpi-monitor-engine from concrete diagnostics lanes]]
- [[Split local-network-fixture FixtureStack into per-protocol service builders]]
- [[Split ripdpi-cloudflare-origin main.rs into focused transport modules]]

## Dependencies

Most child tasks are independent and can be executed in parallel. The following ordering constraints apply:

- **`AdaptivePort` split** (batch 2 P2) should complete before the `adaptive_port_impl.rs` cleanup (batch 2 P3) — the impl split is a direct consequence.
- **`ripdpi-runtime-adaptive` direction fix** and **wildcard re-export removal** should be done together in one PR — they share the same file and a coordinated change avoids two rounds of downstream compilation fixes.
- **`DetectionCheckViewModel` split** (batch 2 P2) depends on **`DetectionHistoryRepository` interface** and **`CommunityComparisonStore` singleton** tasks being done first or in the same PR.

## Risks / open questions

- `RipDpiThemeTokens` public facade: verify no Compose stability annotation relies on the current monolithic file structure before splitting.
- `ripdpi-monitor-engine` lane trait: the trait surface must be stable enough that adding a new lane does not require changing the trait — design the registration point carefully.
- `FixtureStack` TLS material: shared cert/key construction must not be duplicated across per-protocol builders — extract a `TlsMaterial` helper first.
- `ripdpi-runtime-learning`: confirm consumer count with `cargo tree --workspace -i ripdpi-runtime-learning` before removing — it may be a transitive dep not visible in the direct adjacency list.
- `SingletonComponent` growth (71 → 101 modules): the session-component infrastructure (`VpnServiceSessionComponent`, `ProxyServiceSessionComponent`) exists but is almost unused. Session-scoped types (relay credentials, capability evidence, CDN ECH cache) held as singletons survive across VPN sessions — this is a follow-on scoping epic, not in scope here.
