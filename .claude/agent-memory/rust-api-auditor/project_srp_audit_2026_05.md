---
name: SRP and API surface audit May 2026
description: Findings from SRP/god-struct/visibility/trait-ISP audit (May 2026) scoped to individual crate internals, excluding already-tracked epics
type: project
---

Audit run 2026-05-05 against native/rust/crates/.

## God Structs / Oversized Configs

- `ResolvedRelayRuntimeConfig` (ripdpi-relay-core/src/config.rs): 52 pub fields spanning ALL relay backends (hysteria2, tuic, vless, masque, shadowtls, naiveproxy, chain) in one struct. Clear god-struct. 174 LOC config file.
- `RelayRuntime` (ripdpi-relay-core/src/runtime.rs:35-46): 9 fields, 5 of them `Mutex<Option<String>>` for last_target, last_error, last_handshake_error, listener_address, backend. Per-session `.expect()` on Mutex in accept loop (hot path). Should be `ArcSwap<Option<String>>` or `AtomicPtr` for read-dominant telemetry fields.
- `ServicesState` (ripdpi-runtime-services/src/services_state.rs:26-50): 12 fields, 6 `Arc<RwLock<...>>`. pub(crate) fields accessed directly by sibling impl files (encapsulation via module, not accessor methods). Lock-order comment present but no enforcement.

## Oversized Traits (ISP violations)

- `AdaptivePort` (ripdpi-runtime-adaptive/src/adaptive_port.rs): 27 methods across 5 concern groups (hint resolution, adaptive feedback, strategy evolution, morph policy, retry pacing, reprobe reset, persistence). Single implementor: ServicesStateHandle only. Should be split into: AdaptiveHintPort (resolve_*), AdaptiveFeedbackPort (note_*), StrategyEvolutionPort, RetryPacingPort.
- `PolicyPort` (ripdpi-runtime-policy/src/policy_port.rs): 20 methods spanning route selection, direct-path learning, retry penalties, autolearn, persistence. Single implementor: ServicesStateHandle only. Should split into PolicyRoutePort and DirectPathLearningPort.

## Glob Re-export API Surface Bloat

- `ripdpi-diagnostics-classification/src/lib.rs:4` re-exports all 104 pub items from ripdpi-diagnostics-contracts via `pub use ripdpi_diagnostics_contracts::*` at crate root, PLUS duplicates inside `types {}` module (double exposure). Same pattern repeated across: diagnostics-net, diagnostics-candidates, diagnostics-probes, diagnostics-runner, diagnostics-dns, diagnostics-http, diagnostics-telegram, diagnostics-transport, monitor-engine (9+ crates). Each independently re-exposes the same 104-item surface.
- `ripdpi-runtime-policy/src/lib.rs` uses `pub use direct_path_learning::*` and `pub use runtime_policy::*` (51 total pub items).
- `ripdpi-diagnostics-candidates/src/candidates.rs`: 9 glob re-exports from internal submodules; 88 total pub items.

## Visibility Bloat

- `ServicesStateHandle(pub Arc<ServicesState>)` (ripdpi-runtime-services/src/lib.rs:18): inner `Arc<ServicesState>` is `pub`. Callers can reach `.0.cache`, `.0.adaptive_tuning` etc. directly. Should be `pub(crate)` inner.
- `ServicesState` pub(crate) fields (services_state.rs:27-49): all fields are `pub(crate)` and accessed directly in sibling impl files — this is intentional via module boundary but creates tight coupling; accessor methods would be cleaner.

## Hot-Path Contention

- `RelayRuntime`: 5 `Mutex<Option<String/Arc>>` fields locked on every accepted connection (last_target, last_error, last_handshake_error, listener_address, backend). In high-session relay, these serialize. listener_address and backend are write-once; should be `OnceLock`/`ArcSwap`. last_error/last_target/last_handshake_error are telemetry-only writes; `AtomicPtr` or `ArcSwap<Option<String>>` would eliminate per-session lock.
- `ServicesState`: 6 `Arc<RwLock<...>>` on policy hot path; each connection acquires write lock on `adaptive_tuning` twice (resolve + note). Tracked from prior audit, count grew from 4 to 6 Arc<RwLock> fields.

## Known Issues Trend
- RuntimeState Arc<Mutex/RwLock> count: grew from 4 (April) to 6 (May) in ServicesState. WORSE.
- RelayBackend delegation: now uses macro dispatch (dispatch_pooled_backend!) — improved vs raw match arms. BETTER.
- AdaptivePort method count: 27 methods, single implementor. NEW FINDING (not in prior audit).
- Glob re-export proliferation across diagnostics crates: NEW FINDING.

**Why:** Track for epic-srp-and-architecture-refactoring task prioritization.
**How to apply:** Use as baseline for next audit. P2 findings are RelayRuntime Mutex hot path and AdaptivePort/PolicyPort ISP violations. P3 findings are glob re-exports and visibility.
