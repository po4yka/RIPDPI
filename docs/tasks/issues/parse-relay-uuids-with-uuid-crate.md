---
id: RLY-1794036606000501
title: Parse relay UUIDs with the uuid crate
kind: chore
status: done
area: relay
priority: medium
owner: Standard-library migration
parent: EPC-1794036260770217
blocked_by: []
spec_mode: required
openspec_change: parse-relay-uuids-with-uuid-crate
created: 2026-11-07
updated: 2026-11-07
status_detail: Implementation and required local checks passed; awaiting committed acceptance evidence.
closed_at: "2026-11-07T09:54:24Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Implementation 61f6a62b6 passed 295 tests, 8 existing opt-in ignores, formatting, four-package Clippy, metadata, cargo-deny, ci-gates, and independent reviews. TUIC production syntax and credentials remain unchanged.
---

## Goal

Use the uuid crate for VLESS, xHTTP, and Cloudflare origin parsing. Keep their canonical and compact forms and preserve existing TUIC syntax.

## Audit evidence

Audit finding 8 (attachment 4), 2026-11-07, baseline 202f970bb3574980dd2eb87fea002cac8fbe29f7. This is a confirmed replacement candidate, not proof that an implementation is complete. Recheck callers and tests before editing.

## Scope

Replace arbitrary dash removal and hex decoding with Uuid::parse_str and explicit canonical-dashed or compact input-shape checks.

The three migrated parsers accept canonical-dashed and compact input without trimming. They reject URN and braced forms and misplaced dashes. TUIC keeps Uuid::parse_str(config.uuid.trim()), including existing URN and braced forms. Preserve credential bytes and error redaction.

## Acceptance criteria

- Canonical and compact forms of one UUID produce the same independently specified 16 bytes in each relay.
- -550e8400e29b41d4a716446655440000 and other misplaced-dash forms fail validation.
- All four relays follow the documented input forms without changing credential serialization or byte order.
- Extend each owning crate parser suite with canonical/compact/mixed-case valid values, misplaced/extra dashes, wrong length, and invalid digits.
- Named local gates and repository-required independent review pass; record exact implementation SHA and command results. Commit each complete atomic implementation unit on main.

## Dependencies and order

Priority band 2; recommended position 8 in the epic queue. Prerequisites: None. Respect the epic priority and shared-file queue.

No dependency is implied by priority alone. Shared Cargo and Gradle files use one writer; the epic's queue prevents concurrent edits without introducing false semantic blockers.

## Ownership

- `native/rust/crates/ripdpi-vless/src/config.rs`
- `native/rust/crates/ripdpi-xhttp/src/config.rs`
- `native/rust/crates/ripdpi-cloudflare-origin/src/config.rs`
- `native/rust/crates/ripdpi-tuic/src/protocol.rs`

The same writer owns related module tests, direct dependency declarations, and task artifacts. Shared dependency and generated-output lanes follow the epic. The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

## Dependency decision

Use only existing platform or workspace libraries. Check the direct module dependency before editing.

## Verification

- `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-vless -p ripdpi-xhttp -p ripdpi-cloudflare-origin -p ripdpi-tuic --locked`
- `cargo clippy --manifest-path native/rust/Cargo.toml -p ripdpi-vless -p ripdpi-xhttp -p ripdpi-cloudflare-origin -p ripdpi-tuic --all-targets --locked -- -D warnings`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.
