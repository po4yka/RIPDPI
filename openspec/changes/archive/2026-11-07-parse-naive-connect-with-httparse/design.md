## Context

Audit finding 9 at 202f970 is owned by portfolio task PRX-1794036614727167. Read its current callers and tests before implementation; the audit does not authorize unrelated cleanup.

## Goals / Non-Goals

- Goal: The local NaiveProxy HTTP frontend must reject malformed request lines and headers before opening an upstream.
- Non-goal: Change unrelated protocols, stored identities, UI text, lifecycle behavior, or the conditional audit proposals.

## Decisions

- Use httparse::Request for the bounded HTTP head and existing http types for CONNECT authority. Keep the server-side CONNECT policy.
- Use only existing platform or workspace libraries. Check the direct module dependency before editing.
- Extend existing CONNECT round-trip and no-upstream-on-rejection tests with HTTP/1.xyz, extra request-line tokens, malformed headers, repeated auth, partial heads, and tunneled trailing bytes.

## Contracts and ownership

- `native/rust/crates/ripdpi-naiveproxy/src/http_front.rs`
- `native/rust/crates/ripdpi-naiveproxy/src/tests.rs`

Keep the 16 KiB head limit, authentication and duplicate-auth rejection, CONNECT-only behavior, and bytes after the head boundary.

The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

One writer at a time owns native/rust/Cargo.toml, native/rust/Cargo.lock, the fuzz lockfile when affected, gradle/libs.versions.toml, API snapshots, and architecture outputs. Regenerate owned outputs through their tools; do not relax baselines or bless changed golden files. The epic defines the recommended queue; shared-file order is a scheduling constraint, not a semantic dependency.

The owning writer updates all callers and tests in this slice. Prerequisites: None. Respect the epic priority and shared-file queue.

## Risks / Trade-offs

- Standard parsers can accept or reject different input forms: enforce the task's explicit contract with independent boundary vectors.
- Cancellation and persistence can lose resources or state: exercise the stated failure case, not only round trips.
- Dependency changes can affect size, licensing, and Android support: review actual direct and transitive edges before adoption. No baseline relaxation or unrelated compatibility layer is permitted.

## Implementation decisions

- Keep the exact byte-at-a-time head boundary and 16 KiB limit. Keep UTF-8 rejection and case-insensitive CONNECT and Basic scheme handling. Do not add a fixed header-count cap.
- Allocate parser header slots from the bounded input length divided by three, the minimum field size with an LF line ending. This permits many short headers that the old reader accepted.
- Parse the HTTP grammar before authentication. Keep 407 for missing, incorrect, or duplicate authentication; keep 405 for authenticated non-CONNECT methods. Malformed grammar and CONNECT authorities receive 400. Oversized or interrupted reads keep the existing close/error path.
- Require an explicit numeric port and reject user information and malformed IP literals before upstream creation. Error text does not include the peer-supplied method, authority, or port.

## Migration Plan

First reproduce the specified uncovered behavior or establish a passing contract baseline for mechanical cases. Then replace only the owning infrastructure, keep existing stored and wire formats, and run the gates below. No persisted-data or schema migration is planned. If that assumption fails, revise this plan before editing that contract. Roll back the owning implementation commit if required, without deleting user data.

- `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-naiveproxy --locked`
- `cargo clippy --manifest-path native/rust/Cargo.toml -p ripdpi-naiveproxy --all-targets --locked -- -D warnings`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.
