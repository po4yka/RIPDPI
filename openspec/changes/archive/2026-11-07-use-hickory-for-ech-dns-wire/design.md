## Context

Audit finding 10 at 202f970 is owned by portfolio task DNS-1794036623422123. Read its current callers and tests before implementation; the audit does not authorize unrelated cleanup.

## Goals / Non-Goals

- Goal: ECH DNS queries and replies must follow the same DNS wire rules as the main resolver without a second handwritten parser.
- Non-goal: Change unrelated protocols, stored identities, UI text, lifecycle behavior, or the conditional audit proposals.

## Decisions

- Build and decode messages with existing Hickory types. Reuse or expose the smallest safe domain adapter without reversing crate dependency direction.
- Use only existing platform or workspace libraries. Check the direct module dependency before editing.
- Extend existing ECH wire tests with trailing-dot names, compressed names, CNAME, wrong query ID/owner/question/type, malformed lengths, and truncated replies.

## Contracts and ownership

- `native/rust/crates/ripdpi-ech-dns/src/wire.rs`
- `native/rust/crates/ripdpi-dns-resolver/src/transport/wire.rs`

Keep query ID, question/owner/type/CNAME checks, intended IPv4-only behavior, bounded parsing, and stable error categories. Existing reply validation must remain.

The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

One writer at a time owns native/rust/Cargo.toml, native/rust/Cargo.lock, the fuzz lockfile when affected, gradle/libs.versions.toml, API snapshots, and architecture outputs. Regenerate owned outputs through their tools; do not relax baselines or bless changed golden files. The epic defines the recommended queue; shared-file order is a scheduling constraint, not a semantic dependency.

The owning writer updates all callers and tests in this slice. Prerequisites: DNS-1794036588840800

## Risks / Trade-offs

- Standard parsers can accept or reject different input forms: enforce the task's explicit contract with independent boundary vectors.
- Cancellation and persistence can lose resources or state: exercise the stated failure case, not only round trips.
- Dependency changes can affect size, licensing, and Android support: review actual direct and transitive edges before adoption. No baseline relaxation or unrelated compatibility layer is permitted.

## Migration Plan

First reproduce the specified uncovered behavior or establish a passing contract baseline for mechanical cases. Then replace only the owning infrastructure, keep existing stored and wire formats, and run the gates below. No persisted-data or schema migration is planned. If that assumption fails, revise this plan before editing that contract. Roll back the owning implementation commit if required, without deleting user data.

- `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-ech-dns -p ripdpi-dns-resolver --locked`
- `cargo clippy --manifest-path native/rust/Cargo.toml -p ripdpi-ech-dns -p ripdpi-dns-resolver --all-targets --locked -- -D warnings`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.

## Implementation decisions

Use Hickory for name, query, header, and record decoding. Keep the shared response validator before answer extraction. Preserve IPv4 answer order and duplicates, ID checks, and top-level response error tokens. Normalize malformed record errors to `dns_malformed_response` and invalid compressed names to `dns_invalid_name`; the shared validator already rejects these packets before this adapter in production. Keep public Rust signatures. Accept terminal dots and reject invalid compression pointers.

The DNS fuzz target currently tests a separate manual parser. Remove that copy and call the production ECH parser for raw and generated packets. Add owner property tests and run bounded fuzz and mutation checks. Existing dependency versions and resolver ownership stay unchanged.
