## Context

Audit finding 11 (attachment 1) at 202f970 is owned by portfolio task RST-1794036632883567. Read its current callers and tests before implementation; the audit does not authorize unrelated cleanup.

## Goals / Non-Goals

- Goal: UDP association eviction must use the Rust standard heap without changing bounded history or stale-entry handling.
- Non-goal: Change unrelated protocols, stored identities, UI text, lifecycle behavior, or the conditional audit proposals.

## Decisions

- Use BinaryHeap<Reverse<UdpEvictionEntry>> and remove the unused ripdpi-collections crate, its extra operations, and crate wiring after confirming all consumers.
- Use only existing platform or workspace libraries. Check the direct module dependency before editing.
- Use and extend existing UDP tests for stale entries, source-address reuse, eviction order, and repeated activity beyond the history limit.

## Contracts and ownership

- `native/rust/crates/ripdpi-collections`
- `native/rust/crates/ripdpi-tunnel-core/src/io_loop/udp_assoc/eviction.rs`
- `native/rust/crates/ripdpi-tunnel-core/src/io_loop/udp_assoc/tests.rs`

Keep the explicit 2048-entry threshold, heap rebuilding, association ID/generation checks, eviction order, and bounded activity history. Capacity is not a size limit.

The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

One writer at a time owns native/rust/Cargo.toml, native/rust/Cargo.lock, the fuzz lockfile when affected, gradle/libs.versions.toml, API snapshots, and architecture outputs. Regenerate owned outputs through their tools; do not relax baselines or bless changed golden files. The epic defines the recommended queue; shared-file order is a scheduling constraint, not a semantic dependency.

The owning writer updates all callers and tests in this slice. Prerequisites: None. Respect the epic priority and shared-file queue.

## Risks / Trade-offs

- Standard parsers can accept or reject different input forms: enforce the task's explicit contract with independent boundary vectors.
- Cancellation and persistence can lose resources or state: exercise the stated failure case, not only round trips.
- Dependency changes can affect size, licensing, and Android support: review actual direct and transitive edges before adoption. No baseline relaxation or unrelated compatibility layer is permitted.

## Migration Plan

First reproduce the specified uncovered behavior or establish a passing contract baseline for mechanical cases. Then replace only the owning infrastructure, keep existing stored and wire formats, and run the gates below. No persisted-data or schema migration is planned. If that assumption fails, revise this plan before editing that contract. Roll back the owning implementation commit if required, without deleting user data.

- `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-tunnel-core --locked`
- `cargo metadata --manifest-path native/rust/Cargo.toml --locked --no-deps --format-version 1`
- `python3 scripts/ci/check_architecture_health.py --check`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.

## Heap bound

The sole association insertion path evicts at 512 live associations before inserting. Rebuild therefore retains at most 512 entries; the next activity entry remains below 2048. BinaryHeap capacity is only a reservation: check len against the explicit history threshold before rebuilding. Reverse preserves the former min-heap order. Delete only the leaf crate and its single consumer edge; no new workspace edge is added.
