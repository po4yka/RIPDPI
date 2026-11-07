## Context

Audit finding 6 at 202f970 is owned by portfolio task RLY-1794036597359685. Read its current callers and tests before implementation; the audit does not authorize unrelated cleanup.

## Goals / Non-Goals

- Goal: Credential policy must distinguish independent tenants below public and private suffixes.
- Non-goal: Change unrelated protocols, stored identities, UI text, lifecycle behavior, or the conditional audit proposals.

## Decisions

- Define the allowed-origin policy first, then replace the last-label heuristic with an offline PSL implementation that includes private suffixes and standard URL parsing.
- A bundled PSL crate or maintained offline list is a new production dependency. Before adding it, assess license, private-section coverage, list freshness, APK size, maintenance, and compatibility; obtain approval required by repository policy. Creating this task does not approve that dependency.
- Extend CORS tests for alice.github.io versus bob.github.io, independent fly.io tenants, co.uk, same-origin, approved same-site cases, scheme/port changes, IP literals, and malformed Origin.

## Contracts and ownership

- `native/rust/crates/ripdpi-apps-script-core/src/proxy/http_relay.rs`

PSL domain grouping must not itself grant credential access. Define scheme and port treatment, localhost and IP behavior, invalid Origin rejection, and list update policy before implementation.

The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

One writer at a time owns native/rust/Cargo.toml, native/rust/Cargo.lock, the fuzz lockfile when affected, gradle/libs.versions.toml, API snapshots, and architecture outputs. Regenerate owned outputs through their tools; do not relax baselines or bless changed golden files. The epic defines the recommended queue; shared-file order is a scheduling constraint, not a semantic dependency.

The owning writer updates all callers and tests in this slice. Prerequisites: DNS-1794036588840800

## Risks / Trade-offs

- Standard parsers can accept or reject different input forms: enforce the task's explicit contract with independent boundary vectors.
- Cancellation and persistence can lose resources or state: exercise the stated failure case, not only round trips.
- Dependency changes can affect size, licensing, and Android support: review actual direct and transitive edges before adoption. No baseline relaxation or unrelated compatibility layer is permitted.

## Migration Plan

First reproduce the specified uncovered behavior or establish a passing contract baseline for mechanical cases. Then replace only the owning infrastructure, keep existing stored and wire formats, and run the gates below. No persisted-data or schema migration is planned. If that assumption fails, revise this plan before editing that contract. Roll back the owning implementation commit if required, without deleting user data.

- `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-apps-script-core --locked`
- `cargo clippy --manifest-path native/rust/Cargo.toml -p ripdpi-apps-script-core --all-targets --locked -- -D warnings`
- `cargo deny --manifest-path native/rust/Cargo.toml --locked check`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.
