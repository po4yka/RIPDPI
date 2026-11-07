---
id: RLY-1794036597359685
title: Separate CORS sites with the Public Suffix List
kind: bug
status: review
area: relay
priority: high
owner: Standard-library migration
parent: EPC-1794036260770217
blocked_by: [DNS-1794036588840800]
spec_mode: required
openspec_change: separate-cors-sites-with-public-suffix-list
created: 2026-11-07
updated: 2026-11-07
status_detail: Implementation and required local checks passed; awaiting committed acceptance evidence.
---

## Goal

Credential policy must distinguish independent tenants below public and private suffixes.

## Audit evidence

Audit finding 6, 2026-11-07, baseline 202f970bb3574980dd2eb87fea002cac8fbe29f7. This is a confirmed replacement candidate, not proof that an implementation is complete. Recheck callers and tests before editing.

## Scope

Define the allowed-origin policy first, then replace the last-label heuristic with an offline PSL implementation that includes private suffixes and standard URL parsing.

PSL domain grouping must not itself grant credential access. Define scheme and port treatment, localhost and IP behavior, invalid Origin rejection, and list update policy before implementation.

## Acceptance criteria

- An explicitly permitted origin receives only the CORS headers allowed by the documented policy.
- Independent github.io and fly.dev tenants do not receive credential permission solely because they share a public suffix.
- Existing supported same-site cases are reviewed against the explicit policy; malformed or unsupported origins fail closed without network PSL lookup.
- Extend CORS tests for alice.github.io versus bob.github.io, independent fly.dev tenants, co.uk, same-origin, approved same-site cases, scheme/port changes, IP literals, and malformed Origin.
- Named local gates and repository-required independent review pass; record exact implementation SHA and command results. Commit each complete atomic implementation unit on main.

## Dependencies and order

Priority band 1; recommended position 7 in the epic queue. Prerequisites: DNS-1794036588840800

No dependency is implied by priority alone. Shared Cargo and Gradle files use one writer; the epic's queue prevents concurrent edits without introducing false semantic blockers.

## Ownership

- `native/rust/crates/ripdpi-apps-script-core/src/proxy/http_relay.rs`

The same writer owns related module tests, direct dependency declarations, and task artifacts. Shared dependency and generated-output lanes follow the epic. The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

## Dependency decision

A bundled PSL crate or maintained offline list is a new production dependency. Before adding it, assess license, private-section coverage, list freshness, APK size, maintenance, and compatibility; obtain approval required by repository policy. Creating this task does not approve that dependency.

## Verification

- `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-apps-script-core --locked`
- `cargo clippy --manifest-path native/rust/Cargo.toml -p ripdpi-apps-script-core --all-targets --locked -- -D warnings`
- `cargo deny --manifest-path native/rust/Cargo.toml --locked check`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.
