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
---

- [ ] #task Epic - SRP and architecture refactoring across Kotlin and Rust layers #repo/RIPDPI #area/epic #status/backlog ⏫

## Goal

Eliminate god objects and responsibility violations identified by the kotlin-design-auditor and arch-layer-auditor across the Kotlin service layer, Kotlin UI layer, and Rust native crates. Each file or struct in scope should own exactly one axis of change after the refactor.

## Why now

A batch audit surfaced 13 hotspots where a single file mixes 4–10 unrelated responsibilities. Each hotspot is a serial bottleneck: every feature touch, bug fix, or policy change must navigate the full surface even when only a small slice is relevant. Addressing them together reduces the review burden and makes future protocol and UI work parallelisable across the team.

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

**Out of scope:**
- Behavior changes, new features, or performance optimizations
- Detekt baseline extensions (violations must be fixed, not suppressed)
- Any file not named above

## Ship definition

- [ ] All 13 child tasks reach `done`.
- [ ] No detekt baseline entries added; existing suppressed violations in `HomeAnalysisPanels` removed.
- [ ] `ripdpi-monitor-engine/Cargo.toml` lists no concrete lane crates.
- [ ] `cargo deny check` passes with the new Rust dependency graph.
- [ ] All Roborazzi goldens pass (no visual regression).
- [ ] `cargo nextest run` and Android instrumentation tests green on CI.

## Child tasks

**P2 — Kotlin service layer**
- [[Split VpnServiceRuntimeCoordinator into focused coordinators]]
- [[Split DefaultConnectionPolicyResolver into separate policy services]]
- [[Split OwnedStackBrowserService into transport-layer components]]
- [[Split SettingsUiModels into per-feature state packages]]

**P3 — Kotlin UI layer**
- [[Move AdvancedSettingsScreen taxonomy into feature-specific settings modules]]
- [[Split DiagnosticsUiStrategySupport into focused presentation mappers]]
- [[Split DetectionCheckScreen into focused composable components]]
- [[Split HomeAnalysisPanels into single-responsibility panel composables]]
- [[Split RipDpiState theme tokens by component family]]

**P3 — Kotlin service layer**
- [[Split UpstreamRelaySupervisorSupport into merge, validation, and resolution modules]]

**P3 — Rust native**
- [[Decouple ripdpi-monitor-engine from concrete diagnostics lanes]]
- [[Split local-network-fixture FixtureStack into per-protocol service builders]]
- [[Split ripdpi-cloudflare-origin main.rs into focused transport modules]]

## Dependencies

No child task depends on another child task in this epic — all 13 can be executed independently and in parallel.

## Risks / open questions

- `RipDpiThemeTokens` public facade: verify no Compose stability annotation relies on the current monolithic file structure before splitting.
- `ripdpi-monitor-engine` lane trait: the trait surface must be stable enough that adding a new lane does not require changing the trait — design the registration point carefully.
- `FixtureStack` TLS material: shared cert/key construction must not be duplicated across per-protocol builders — extract a `TlsMaterial` helper first.
