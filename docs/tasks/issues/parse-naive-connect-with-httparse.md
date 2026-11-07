---
id: PRX-1794036614727167
title: Parse NaiveProxy CONNECT heads with httparse
kind: bug
status: review
area: proxy
priority: medium
owner: Standard-library migration
parent: EPC-1794036260770217
blocked_by: []
spec_mode: required
openspec_change: parse-naive-connect-with-httparse
created: 2026-11-07
updated: 2026-11-07
status_detail: Implementation and required local checks passed; awaiting committed acceptance evidence.
---

## Goal

The local NaiveProxy HTTP frontend must reject malformed request lines and headers before opening an upstream.

## Audit evidence

Audit finding 9, 2026-11-07, baseline 202f970bb3574980dd2eb87fea002cac8fbe29f7. This is a confirmed replacement candidate, not proof that an implementation is complete. Recheck callers and tests before editing.

## Scope

Use httparse::Request for the bounded HTTP head and existing http types for CONNECT authority. Keep the server-side CONNECT policy.

Keep the 16 KiB head limit, authentication and duplicate-auth rejection, CONNECT-only behavior, and bytes after the head boundary.

## Acceptance criteria

- A valid authenticated CONNECT completes the existing h2 Naive padding round trip.
- HTTP/1.xyz, extra tokens, invalid authority, duplicate auth, and oversized heads are rejected before upstream creation.
- Partial input and payload bytes after the header boundary keep their existing stream behavior.
- Extend existing CONNECT round-trip and no-upstream-on-rejection tests with HTTP/1.xyz, extra request-line tokens, malformed headers, repeated auth, partial heads, and tunneled trailing bytes.
- Named local gates and repository-required independent review pass; record exact implementation SHA and command results. Commit each complete atomic implementation unit on main.

## Dependencies and order

Priority band 2; recommended position 9 in the epic queue. Prerequisites: None. Respect the epic priority and shared-file queue.

No dependency is implied by priority alone. Shared Cargo and Gradle files use one writer; the epic's queue prevents concurrent edits without introducing false semantic blockers.

## Ownership

- `native/rust/crates/ripdpi-naiveproxy/src/http_front.rs`
- `native/rust/crates/ripdpi-naiveproxy/src/tests.rs`

The same writer owns related module tests, direct dependency declarations, and task artifacts. Shared dependency and generated-output lanes follow the epic. The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

## Dependency decision

Use only existing platform or workspace libraries. Check the direct module dependency before editing.

## Verification

- `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-naiveproxy --locked`
- `cargo clippy --manifest-path native/rust/Cargo.toml -p ripdpi-naiveproxy --all-targets --locked -- -D warnings`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.
