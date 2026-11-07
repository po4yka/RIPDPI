## Context

Audit finding 8 (attachment 4) at 202f970 is owned by portfolio task RLY-1794036606000501. Read its current callers and tests before implementation; the audit does not authorize unrelated cleanup.

## Goals / Non-Goals

- Goal: VLESS, xHTTP, Cloudflare origin, and TUIC must validate the same supported UUID forms.
- Non-goal: Change unrelated protocols, stored identities, UI text, lifecycle behavior, or the conditional audit proposals.

## Decisions

- Replace arbitrary dash removal and hex decoding with Uuid::parse_str and explicit canonical-dashed or compact input-shape checks.
- Use only existing platform or workspace libraries. Check the direct module dependency before editing.
- Extend each owning crate parser suite with canonical/compact/mixed-case valid values, misplaced/extra dashes, wrong length, and invalid digits.

## Contracts and ownership

- `native/rust/crates/ripdpi-vless/src/config.rs`
- `native/rust/crates/ripdpi-xhttp/src/config.rs`
- `native/rust/crates/ripdpi-cloudflare-origin/src/config.rs`
- `native/rust/crates/ripdpi-tuic/src/protocol.rs`

Keep supported canonical and compact forms and byte order. Do not silently add URN or braced forms; reject misplaced dashes with redacted errors.

The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

One writer at a time owns native/rust/Cargo.toml, native/rust/Cargo.lock, the fuzz lockfile when affected, gradle/libs.versions.toml, API snapshots, and architecture outputs. Regenerate owned outputs through their tools; do not relax baselines or bless changed golden files. The epic defines the recommended queue; shared-file order is a scheduling constraint, not a semantic dependency.

The owning writer updates all callers and tests in this slice. Prerequisites: None. Respect the epic priority and shared-file queue.

## Risks / Trade-offs

- Standard parsers can accept or reject different input forms: enforce the task's explicit contract with independent boundary vectors.
- Cancellation and persistence can lose resources or state: exercise the stated failure case, not only round trips.
- Dependency changes can affect size, licensing, and Android support: review actual direct and transitive edges before adoption. No baseline relaxation or unrelated compatibility layer is permitted.

## Migration Plan

First reproduce the specified uncovered behavior or establish a passing contract baseline for mechanical cases. Then replace only the owning infrastructure, keep existing stored and wire formats, and run the gates below. No persisted-data or schema migration is planned. If that assumption fails, revise this plan before editing that contract. Roll back the owning implementation commit if required, without deleting user data.

- `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-vless -p ripdpi-xhttp -p ripdpi-cloudflare-origin -p ripdpi-tuic --locked`
- `cargo clippy --manifest-path native/rust/Cargo.toml -p ripdpi-vless -p ripdpi-xhttp -p ripdpi-cloudflare-origin -p ripdpi-tuic --all-targets --locked -- -D warnings`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.
