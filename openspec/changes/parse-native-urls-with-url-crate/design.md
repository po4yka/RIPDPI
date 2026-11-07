## Context

Audit finding 5 at 202f970 is owned by portfolio task DNS-1794036588840800. Read its current callers and tests before implementation; the audit does not authorize unrelated cleanup.

## Goals / Non-Goals

- Goal: Native redirect and encrypted-DNS endpoint handling must extract valid hosts and resolve relative references against the current URL.
- Non-goal: Change unrelated protocols, stored identities, UI text, lifecycle behavior, or the conditional audit proposals.

## Decisions

- Use the existing url crate for parsing, host_str, and join. Carry the current URL through redirect chains and omit fragments from HTTP request targets.
- Use only existing platform or workspace libraries. Check the direct module dependency before editing.
- Extend redirect and endpoint tests for query-only absolute URLs, path/query/scheme-relative references, IPv6 and ports, invalid schemes, and absolute-to-relative chains.

## Contracts and ownership

- `native/rust/crates/ripdpi-apps-script-core/src/domain_fronter/redirect.rs`
- `native/rust/crates/ripdpi-apps-script-core/src/domain_fronter.rs`
- `native/rust/crates/ripdpi-ech-dns/src/endpoints/parse.rs`
- `native/rust/crates/ripdpi-ech-dns/src/endpoints/builder.rs`

Keep Apps Script front host and SNI transport, scheme restrictions, redirect budget, endpoint ports, and error redaction. Do not turn on an ordinary automatic HTTP redirect client.

The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

One writer at a time owns native/rust/Cargo.toml, native/rust/Cargo.lock, the fuzz lockfile when affected, gradle/libs.versions.toml, API snapshots, and architecture outputs. Regenerate owned outputs through their tools; do not relax baselines or bless changed golden files. The epic defines the recommended queue; shared-file order is a scheduling constraint, not a semantic dependency.

The owning writer updates all callers and tests in this slice. Prerequisites: None. Respect the epic priority and shared-file queue.

## Risks / Trade-offs

- Standard parsers can accept or reject different input forms: enforce the task's explicit contract with independent boundary vectors.
- Cancellation and persistence can lose resources or state: exercise the stated failure case, not only round trips.
- Dependency changes can affect size, licensing, and Android support: review actual direct and transitive edges before adoption. No baseline relaxation or unrelated compatibility layer is permitted.

## Migration Plan

First reproduce the specified uncovered behavior or establish a passing contract baseline for mechanical cases. Then replace only the owning infrastructure, keep existing stored and wire formats, and run the gates below. No persisted-data or schema migration is planned. If that assumption fails, revise this plan before editing that contract. Roll back the owning implementation commit if required, without deleting user data.

- `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-apps-script-core -p ripdpi-ech-dns --locked`
- `cargo clippy --manifest-path native/rust/Cargo.toml -p ripdpi-apps-script-core -p ripdpi-ech-dns --all-targets --locked -- -D warnings`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.
