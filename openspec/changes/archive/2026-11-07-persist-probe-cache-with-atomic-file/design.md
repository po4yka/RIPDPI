## Context

Audit finding 12 (attachment 5) at 202f970 is owned by portfolio task DGN-1794036642254618. Read its current callers and tests before implementation; the audit does not authorize unrelated cleanup.

## Goals / Non-Goals

- Goal: An interrupted probe-cache write must not destroy the previous complete cache.
- Non-goal: Change unrelated protocols, stored identities, UI text, lifecycle behavior, or the conditional audit proposals.

## Decisions

- Use the existing Android AtomicFile pattern for both reads and writes, with finishWrite and failWrite.
- Use only existing platform or workspace libraries. Check the direct module dependency before editing.
- Extend owning cache tests for old-file retention after failed write, successful replacement/readback, corrupt input, TTL, and eviction boundaries.

## Contracts and ownership

- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/ProbeResultCache.kt`

Keep the mutex, 24-hour TTL, 10-entry limit, JSON shape, and safe fallback for an already corrupt file. No database or schema migration.

The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

One writer at a time owns native/rust/Cargo.toml, native/rust/Cargo.lock, the fuzz lockfile when affected, gradle/libs.versions.toml, API snapshots, and architecture outputs. Regenerate owned outputs through their tools; do not relax baselines or bless changed golden files. The epic defines the recommended queue; shared-file order is a scheduling constraint, not a semantic dependency.

The owning writer updates all callers and tests in this slice. Prerequisites: None. Respect the epic priority and shared-file queue.

## Risks / Trade-offs

- Standard parsers can accept or reject different input forms: enforce the task's explicit contract with independent boundary vectors.
- Cancellation and persistence can lose resources or state: exercise the stated failure case, not only round trips.
- Dependency changes can affect size, licensing, and Android support: review actual direct and transitive edges before adoption. No baseline relaxation or unrelated compatibility layer is permitted.

## Migration Plan

First reproduce the specified uncovered behavior or establish a passing contract baseline for mechanical cases. Then replace only the owning infrastructure, keep existing stored and wire formats, and run the gates below. No persisted-data or schema migration is planned. If that assumption fails, revise this plan before editing that contract. Roll back the owning implementation commit if required, without deleting user data.

- `./gradlew :core:diagnostics:testDebugUnitTest`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.

## Observed verification scope

Test backup recovery, successful replacement, corrupt JSON, clear, TTL, and eviction on Robolectric SDK 27 and 35. Test the .new layout and blocked startWrite on SDK 35. The initial default-SDK .new blocker did not cause a write failure under the legacy backup layout; use an explicit SDK for this layout-specific fixture. These host tests do not establish mid-write rollback, process-kill, or device filesystem acceptance. Cleanup after an acquired write stream uses finally; no generic exception catch or quality suppression is needed.
