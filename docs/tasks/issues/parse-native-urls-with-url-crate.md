---
id: DNS-1794036588840800
title: Resolve native redirects and DNS endpoints with url
kind: bug
status: done
area: dns
priority: high
owner: Standard-library migration
parent: EPC-1794036260770217
blocked_by: []
spec_mode: required
openspec_change: parse-native-urls-with-url-crate
created: 2026-11-07
updated: 2026-11-07
status_detail: Implementation and required local checks passed; awaiting committed acceptance evidence.
closed_at: "2026-11-07T09:16:14Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Implementation 596ce660e passed 98 tests, formatting, both-package Clippy, locked metadata, cargo-deny, ci-gates, and independent correctness and cancellation reviews.
---

## Goal

Native redirect and encrypted-DNS endpoint handling must extract valid hosts and resolve relative references against the current URL.

## Audit evidence

Audit finding 5, 2026-11-07, baseline 202f970bb3574980dd2eb87fea002cac8fbe29f7. This is a confirmed replacement candidate, not proof that an implementation is complete. Recheck callers and tests before editing.

## Scope

Use the existing url crate for parsing, host_str, and join. Carry the current URL through redirect chains and omit fragments from HTTP request targets.

Keep Apps Script front host and SNI transport, scheme restrictions, redirect budget, endpoint ports, and error redaction. Do not turn on an ordinary automatic HTTP redirect client.

## Acceptance criteria

- https://host.example?token=x yields host host.example and request target /?token=x.
- Invalid or disallowed redirect schemes and malformed endpoints fail within the existing redirect budget without leaking query credentials.
- After an absolute redirect, a relative reference resolves against the new URL while the intended front/SNI transport remains in use.
- Extend redirect and endpoint tests for query-only absolute URLs, path/query/scheme-relative references, IPv6 and ports, invalid schemes, and absolute-to-relative chains.
- Named local gates and repository-required independent review pass; record exact implementation SHA and command results. Commit each complete atomic implementation unit on main.

## Dependencies and order

Priority band 1; recommended position 6 in the epic queue. Prerequisites: None. Respect the epic priority and shared-file queue.

No dependency is implied by priority alone. Shared Cargo and Gradle files use one writer; the epic's queue prevents concurrent edits without introducing false semantic blockers.

## Ownership

- `native/rust/crates/ripdpi-apps-script-core/src/domain_fronter/redirect.rs`
- `native/rust/crates/ripdpi-apps-script-core/src/domain_fronter.rs`
- `native/rust/crates/ripdpi-ech-dns/src/endpoints/parse.rs`
- `native/rust/crates/ripdpi-ech-dns/src/endpoints/builder.rs`

The same writer owns related module tests, direct dependency declarations, and task artifacts. Shared dependency and generated-output lanes follow the epic. The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

## Dependency decision

Use only existing platform or workspace libraries. Check the direct module dependency before editing.

## Verification

- `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-apps-script-core -p ripdpi-ech-dns --locked`
- `cargo clippy --manifest-path native/rust/Cargo.toml -p ripdpi-apps-script-core -p ripdpi-ech-dns --all-targets --locked -- -D warnings`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.
