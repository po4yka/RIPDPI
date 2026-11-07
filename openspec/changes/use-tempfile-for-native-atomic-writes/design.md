## Context

Audit finding 13 at 202f970 is owned by portfolio task RST-1794036651029797. Read its current callers and tests before implementation; the audit does not authorize unrelated cleanup.

## Goals / Non-Goals

- Goal: The three native state writers must share safe temporary-file lifecycle behavior without losing durability or file permissions.
- Non-goal: Change unrelated protocols, stored identities, UI text, lifecycle behavior, or the conditional audit proposals.

## Decisions

- Use NamedTempFile::new_in in the destination directory, write and sync the file, persist it, then sync the parent directory as required by the existing contract.
- tempfile is already used for tests but will enter the production graph. Assess license, transitive dependencies, binary size, platform support, and approval required by repository policy before promotion.
- Extend existing failed_replacement_cleans_temporary_file, cache_persist_failure_preserves_existing_file, and cache_persist_preserves_existing_file_permissions tests for all production writers.

## Contracts and ownership

- `native/rust/crates/ripdpi-runtime-policy/src/runtime_policy/autolearn/store.rs`
- `native/rust/crates/ripdpi-runtime-policy/src/runtime_policy/cache/connection.rs`
- `native/rust/crates/ripdpi-runtime-adaptive/src/adaptive_tuning/persistence/file_io.rs`

Keep size limits, restrictive permissions and permission preservation, old-file retention on failure, and sync_all ordering. Drop cleanup is not a SIGKILL guarantee.

The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

One writer at a time owns native/rust/Cargo.toml, native/rust/Cargo.lock, the fuzz lockfile when affected, gradle/libs.versions.toml, API snapshots, and architecture outputs. Regenerate owned outputs through their tools; do not relax baselines or bless changed golden files. The epic defines the recommended queue; shared-file order is a scheduling constraint, not a semantic dependency.

The owning writer updates all callers and tests in this slice. Prerequisites: None. Respect the epic priority and shared-file queue.

## Risks / Trade-offs

- Standard parsers can accept or reject different input forms: enforce the task's explicit contract with independent boundary vectors.
- Cancellation and persistence can lose resources or state: exercise the stated failure case, not only round trips.
- Dependency changes can affect size, licensing, and Android support: review actual direct and transitive edges before adoption. No baseline relaxation or unrelated compatibility layer is permitted.

## Migration Plan

First reproduce the specified uncovered behavior or establish a passing contract baseline for mechanical cases. Then replace only the owning infrastructure, keep existing stored and wire formats, and run the gates below. No persisted-data or schema migration is planned. If that assumption fails, revise this plan before editing that contract. Roll back the owning implementation commit if required, without deleting user data.

- `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-runtime-policy -p ripdpi-runtime-adaptive --locked`
- `cargo clippy --manifest-path native/rust/Cargo.toml -p ripdpi-runtime-policy -p ripdpi-runtime-adaptive --all-targets --locked -- -D warnings`
- `cargo deny --manifest-path native/rust/Cargo.toml --locked check`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.
