---
id: RLY-1794036580419313
title: Use library constant-time SOCKS credential comparison
kind: chore
status: todo
area: relay
priority: high
owner: Standard-library migration
parent: EPC-1794036260770217
blocked_by: []
spec_mode: required
openspec_change: use-library-socks-secret-comparison
created: 2026-11-07
updated: 2026-11-07
---

## Goal

SOCKS authentication must use the existing maintained constant-time comparison primitive in both runtime paths.

## Audit evidence

Audit finding 7, 2026-11-07, baseline 202f970bb3574980dd2eb87fea002cac8fbe29f7. This is a confirmed replacement candidate, not proof that an implementation is complete. Recheck callers and tests before editing.

## Scope

Replace the relay-core XOR fold with constant_time_eq::constant_time_eq, already used by proxy-runtime.

Evaluate username and password comparisons without short-circuit; preserve unequal-length rejection and redacted authentication errors. Do not claim to hide credential lengths.

## Acceptance criteria

- Correct username and password authenticate through the credentialed listener.
- An incorrect username or password is rejected without logging either credential.
- Both authentication paths use the same library primitive; external SOCKS negotiation and unequal-length results remain unchanged.
- Extend the existing successful and rejected credential tests for first and last byte differences, unequal lengths, and both operand failures.
- Named local gates and repository-required independent review pass; record exact implementation SHA and command results. Commit each complete atomic implementation unit on main.

## Dependencies and order

Priority band 1; recommended position 5 in the epic queue. Prerequisites: None. Respect the epic priority and shared-file queue.

No dependency is implied by priority alone. Shared Cargo and Gradle files use one writer; the epic's queue prevents concurrent edits without introducing false semantic blockers.

## Ownership

- `native/rust/crates/ripdpi-relay-core/src/socks/auth.rs`

The same writer owns related module tests, direct dependency declarations, and task artifacts. Shared dependency and generated-output lanes follow the epic. The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

## Dependency decision

Use only existing platform or workspace libraries. Check the direct module dependency before editing.

## Verification

- `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-relay-core -p ripdpi-proxy-runtime --locked`
- `cargo clippy --manifest-path native/rust/Cargo.toml -p ripdpi-relay-core --all-targets --locked -- -D warnings`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.
