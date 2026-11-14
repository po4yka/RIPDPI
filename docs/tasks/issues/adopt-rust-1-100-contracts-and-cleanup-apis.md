---
id: RST-1794629289019737
title: Adopt Rust 1.100 contracts and cleanup APIs
kind: chore
status: review
area: rust-native
priority: medium
owner: Rust maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: rust100-contracts-cleanup
created: 2026-11-14
updated: 2026-11-14
---

## Goal

Use Rust 1.100 types and standard cleanup APIs while preserving relay errors, listener retries, cancellation, proxy state, and DNS validation.

## Acceptance criteria

- Unsupported UDP remains an error; its success type is uninhabited.
- Listener and cleanup tests pass without changed security checks.
- Affected crate tests, workspace Clippy, CI source gates, and independent review pass.

## Ownership

One writer owns the affected Rust crate files and these planning artifacts. No shared manifest, lockfile, schema, locale, or baseline changes are planned.
