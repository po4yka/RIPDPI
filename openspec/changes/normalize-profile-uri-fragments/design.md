## Context

Audit finding 1 (attachment 2) at 202f970 is owned by portfolio task DAT-1794036514216544. Read its current callers and tests before implementation; the audit does not authorize unrelated cleanup.

## Goals / Non-Goals

- Goal: Imported profile names must preserve plus signs, percent characters, and Unicode without a second decode.
- Non-goal: Change unrelated protocols, stored identities, UI text, lifecycle behavior, or the conditional audit proposals.

## Decisions

- Use java.net.URI fragment semantics once. Make raw and decoded fragment inputs explicit; remove URLDecoder from already decoded inputs.
- Use only existing platform or workspace libraries. Check the direct module dependency before editing.
- Extend existing runtime-state ProxyUriCodecTest and core/data ProxyUriCodecTest and ProxyUriCodecExportTest.

## Contracts and ownership

- `core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/uri/ProxyUriCodec.kt`

Keep each supported share-link format and export/import round trips. Do not change relay activation or stored profile identifiers.

The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

One writer at a time owns native/rust/Cargo.toml, native/rust/Cargo.lock, the fuzz lockfile when affected, gradle/libs.versions.toml, API snapshots, and architecture outputs. Regenerate owned outputs through their tools; do not relax baselines or bless changed golden files. The epic defines the recommended queue; shared-file order is a scheduling constraint, not a semantic dependency.

The owning writer updates all callers and tests in this slice. Prerequisites: None. Respect the epic priority and shared-file queue.

## Risks / Trade-offs

- Standard parsers can accept or reject different input forms: enforce the task's explicit contract with independent boundary vectors.
- Cancellation and persistence can lose resources or state: exercise the stated failure case, not only round trips.
- Dependency changes can affect size, licensing, and Android support: review actual direct and transitive edges before adoption. No baseline relaxation or unrelated compatibility layer is permitted.

## Migration Plan

First reproduce the specified uncovered behavior or establish a passing contract baseline for mechanical cases. Then replace only the owning infrastructure, keep existing stored and wire formats, and run the gates below. No persisted-data or schema migration is planned. If that assumption fails, revise this plan before editing that contract. Roll back the owning implementation commit if required, without deleting user data.

- `./gradlew :core:data:runtime-state:testDebugUnitTest :core:data:testDebugUnitTest`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.
