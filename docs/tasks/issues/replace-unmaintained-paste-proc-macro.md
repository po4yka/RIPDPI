---
title: Replace unmaintained paste proc-macro dependency
type: task
status: backlog
area: rust-native
priority: low
owner: Native security maintainer
parent: null
blocks: []
blocked_by: []
created: 2026-07-13
updated: 2026-07-13
---

## Goal

Remove the `RUSTSEC-2024-0436` waiver by upgrading or replacing the `netlink-packet-core` path that still pulls `paste 1.0.15`.

## Review deadline

Re-evaluate the waiver no later than 2026-10-11. The machine-checked expiry in `native/rust/advisory-waivers.toml` intentionally blocks CI on that date until this task is reviewed.

## Acceptance criteria

- `cargo tree --manifest-path native/rust/Cargo.toml -i paste` no longer reports `paste 1.0.15`.
- `RUSTSEC-2024-0436` is removed from `native/rust/deny.toml` and `native/rust/advisory-waivers.toml`.
- `cargo deny --manifest-path native/rust/Cargo.toml check advisories` and `python3 scripts/ci/check_rust_advisory_waivers.py` pass.
