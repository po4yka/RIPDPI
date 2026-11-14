---
id: RST-1794629289019737
title: Adopt Rust 1.100 contracts and cleanup APIs
kind: chore
status: done
area: rust-native
priority: medium
owner: Rust maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: rust100-contracts-cleanup
created: 2026-11-14
updated: 2026-11-14
closed_at: "2026-11-14T04:34:15Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: 1536 affected tests passed; workspace lint and CI source gates passed; full workspace Android arm64 and armv7 cross-checks passed; independent general, async, and JNI reviews passed.
---

## Goal

Use Rust 1.100 types and standard cleanup APIs while preserving relay errors, listener retries, cancellation, proxy state, and DNS validation.

## Acceptance criteria

- Unsupported UDP remains an error; its success type is uninhabited.
- Listener and cleanup tests pass without changed security checks.
- Affected crate tests, workspace Clippy, CI source gates, and independent review pass.

## Ownership

One writer owns the affected Rust crate files and these planning artifacts. No shared manifest, lockfile, schema, locale, or baseline changes are planned.
