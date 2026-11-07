## Context

Audit finding 6 at 202f970 is owned by portfolio task RLY-1794036597359685. Read its current callers and tests before implementation; the audit does not authorize unrelated cleanup.

## Goals / Non-Goals

- Goal: Credential policy must distinguish independent tenants below public and private suffixes.
- Non-goal: Change unrelated protocols, stored identities, UI text, lifecycle behavior, or the conditional audit proposals.

## Decisions

- Define the allowed-origin policy first, then replace the last-label heuristic with an offline PSL implementation that includes private suffixes and standard URL parsing.
- A bundled PSL crate or maintained offline list is a new production dependency. Before adding it, assess license, private-section coverage, list freshness, APK size, maintenance, and compatibility; obtain approval required by repository policy. Creating this task does not approve that dependency.
- Extend CORS tests for alice.github.io versus bob.github.io, independent fly.dev tenants, co.uk, same-origin, approved same-site cases, scheme/port changes, IP literals, and malformed Origin.

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

## Approved dependency and origin policy (2026-11-07)

The user approved psl 2.1.248 and the following policy in this chat. Use a caret requirement and the exact Cargo.lock resolution. Use the bundled ICANN and private sections. Update the list through a reviewed dependency bump; do not fetch it at runtime. Relay maintainers own these updates and the private-tenant regression tests.

Credential access requires equal URL schemes and effective ports, plus an equal registrable domain. This keeps the existing approved same-site subdomain use case. Exact hosts cover localhost and IP literals. Reject malformed or multiple Origin values and user information, paths, queries, and fragments. PSL grouping is one check in this explicit policy; it does not grant access on its own.

Correct the audit example: the official PSL has fly.dev and sprites.app under the comment for Fly.io. It does not list fly.io as a suffix. Test alice.fly.dev against bob.fly.dev. Ordinary subdomains of fly.io retain the same-site result. Source: https://publicsuffix.org/list/public_suffix_list.dat (2026-11-06 snapshot).

The psl package declares MIT OR Apache-2.0; its sole dependency, psl-types 2.0.11, has the same choice. The upstream PSL data carries MPL-2.0 provenance. Keep attribution. Source review of both published packages found no build scripts, proc macros, runtime I/O, or unexplained behavior. The psl package is 208168 compressed bytes; generated source is about 2.58 MB. These are source sizes, not APK or native binary deltas. Measure the relevant Android binary before acceptance. Sources: https://crates.io/api/v1/crates/psl/2.1.248 and https://github.com/addr-rs/psl/tree/639880ab76dac50b12beaa99f5b19c364e39024f .

The credential check uses the same absolute target URL as the relay. This also covers absolute-form HTTP request targets; do not check a different host from the one passed to Apps Script. New Android size evidence is a same-profile arm64-v8a android-jni-dev comparison of normalized stripped ELF copies. It does not establish release or APK size.

Reject whitespace and control characters in the entire raw Origin, including the scheme, before URL parsing. WHATWG parsing can remove embedded tabs; that repair is not valid for a serialized Origin. The wire regression covers this boundary.

## Observed Android size boundary

Both owner builds passed with arm64-v8a and android-jni-dev. NDK 29 tools normalized copies only. The stripped relay ELF changed from 37004000 to 38222616 bytes (delta +1218616, +3.29%). Text changed from 35228905 to 36447849 bytes. This is the whole task delta, including the bundled PSL and CORS checks; it is not an isolated library-only or shipping APK measurement. Release size and the full multi-ABI size policy gate were not checked. No size baseline was changed.

Final normalized ELF SHA-256: a4c7a11d28659c14e93c20b48e62dd90656cb30a93c42007ded0abc5ababd862. Native source input digest: 5441d2264c55d49cbd832b3898da223b853f5031c42f5c4f450736049d2ae575.
