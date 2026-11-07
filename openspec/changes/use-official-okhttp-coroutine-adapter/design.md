## Context

Audit finding 4 at 202f970 is owned by portfolio task DGN-1794036571954184. Read its current callers and tests before implementation; the audit does not authorize unrelated cleanup.

## Goals / Non-Goals

- Goal: HTTP probes must cancel calls and release responses when cancellation wins after the callback but before coroutine delivery.
- Non-goal: Change unrelated protocols, stored identities, UI text, lifecycle behavior, or the conditional audit proposals.

## Decisions

- Use executeAsync for callback delivery and undelivered-response cleanup. Close HEAD responses before reading listener results.
- Payload reads remain blocking IO. Run the full response.use operation in an IO child and await its DTO in a coroutineScope. If cancellation leaves the child in flight, cancel its Call in the awaiting scope's finally block before the scope waits for that child. Return no open Response from the lexical scope.
- Catch only IOException in the existing network-failure paths. Preserve cancellation and programming exceptions, response status on body failure, payload bounds, and normal EventListener completion.
- The official module is Apache-2.0 and follows the existing OkHttp version pin. Coroutines and Okio are already in these modules; inspect the resolved graph for version changes.

- Use okhttp-coroutines Call.executeAsync with response.use in the repeated probe adapters. Keep response-body processing on an appropriate IO dispatcher.
- Add the official okhttp-coroutines module at the version pinned for OkHttp in gradle/libs.versions.toml. Verify publication, licenses, transitive dependencies, and Android compatibility. One writer owns the version catalog and affected build files.
- Extend existing probe tests with deterministic cancellation after onResponse and before dispatch; assert response closure and test normal and failed payloads with MockWebServer.

## Contracts and ownership

- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/DefaultProbeReplayService.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/StrategyProbeService.kt`
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/RelayCapabilityProbe.kt`
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/QualityHttpPayloadProbe.kt`

Keep call deadlines, EventListener, status and payload checks, socket routing, and cancellation propagation. Do not replace specialized probe payloads.

The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

One writer at a time owns native/rust/Cargo.toml, native/rust/Cargo.lock, the fuzz lockfile when affected, gradle/libs.versions.toml, API snapshots, and architecture outputs. Regenerate owned outputs through their tools; do not relax baselines or bless changed golden files. The epic defines the recommended queue; shared-file order is a scheduling constraint, not a semantic dependency.

The owning writer updates all callers and tests in this slice. Prerequisites: None. Respect the epic priority and shared-file queue.

## Risks / Trade-offs

- Standard parsers can accept or reject different input forms: enforce the task's explicit contract with independent boundary vectors.
- Cancellation and persistence can lose resources or state: exercise the stated failure case, not only round trips.
- Dependency changes can affect size, licensing, and Android support: review actual direct and transitive edges before adoption. No baseline relaxation or unrelated compatibility layer is permitted.

## Migration Plan

First reproduce the specified uncovered behavior or establish a passing contract baseline for mechanical cases. Then replace only the owning infrastructure, keep existing stored and wire formats, and run the gates below. No persisted-data or schema migration is planned. If that assumption fails, revise this plan before editing that contract. Roll back the owning implementation commit if required, without deleting user data.

- `./gradlew :core:diagnostics:testDebugUnitTest :core:service:testDebugUnitTest`
- `./gradlew staticAnalysis`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.
