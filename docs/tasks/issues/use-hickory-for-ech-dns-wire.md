---
id: DNS-1794036623422123
title: Use Hickory for ECH DNS wire messages
kind: chore
status: blocked
area: dns
priority: medium
owner: Standard-library migration
parent: EPC-1794036260770217
blocked_by: [DNS-1794036588840800]
spec_mode: required
openspec_change: use-hickory-for-ech-dns-wire
created: 2026-11-07
updated: 2026-11-07
status_detail: Wait for the prerequisite contracts recorded in blocked_by; this is a planned implementation task.
---

## Goal

ECH DNS queries and replies must follow the same DNS wire rules as the main resolver without a second handwritten parser.

## Audit evidence

Audit finding 10, 2026-11-07, baseline 202f970bb3574980dd2eb87fea002cac8fbe29f7. This is a confirmed replacement candidate, not proof that an implementation is complete. Recheck callers and tests before editing.

## Scope

Build and decode messages with existing Hickory types. Reuse or expose the smallest safe domain adapter without reversing crate dependency direction.

Keep query ID, question/owner/type/CNAME checks, intended IPv4-only behavior, bounded parsing, and stable error categories. Existing reply validation must remain.

## Acceptance criteria

- example.com and example.com. produce equivalent valid DNS questions and retain the requested transaction ID.
- Wrong-query, malformed, truncated, and invalid-owner replies remain rejected by the shared validation contract.
- Valid compressed and CNAME replies keep the existing address result and endpoint configuration behavior.
- Extend existing ECH wire tests with trailing-dot names, compressed names, CNAME, wrong query ID/owner/question/type, malformed lengths, and truncated replies.
- Named local gates and repository-required independent review pass; record exact implementation SHA and command results. Commit each complete atomic implementation unit on main.

## Dependencies and order

Priority band 2; recommended position 10 in the epic queue. Prerequisites: DNS-1794036588840800

No dependency is implied by priority alone. Shared Cargo and Gradle files use one writer; the epic's queue prevents concurrent edits without introducing false semantic blockers.

## Ownership

- `native/rust/crates/ripdpi-ech-dns/src/wire.rs`
- `native/rust/crates/ripdpi-dns-resolver/src/transport/wire.rs`

The same writer owns related module tests, direct dependency declarations, and task artifacts. Shared dependency and generated-output lanes follow the epic. The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

## Dependency decision

Use only existing platform or workspace libraries. Check the direct module dependency before editing.

## Verification

- `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-ech-dns -p ripdpi-dns-resolver --locked`
- `cargo clippy --manifest-path native/rust/Cargo.toml -p ripdpi-ech-dns -p ripdpi-dns-resolver --all-targets --locked -- -D warnings`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.
