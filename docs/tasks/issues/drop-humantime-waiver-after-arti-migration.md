---
id: RST-1794933042376654
title: Drop humantime waiver after Arti migrates
kind: chore
status: backlog
area: rust-native
priority: low
owner: Native security maintainer
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-17
updated: 2026-11-17
spec_reason: dependency-only
---

## Goal

`native/rust/deny.toml` ignores RUSTSEC-2026-0542 (humantime unmaintained) because Arti 0.48.0, the latest release, depends on `humantime` and `humantime-serde` in `arti-client`, `tor-config`, `tor-circmgr`, `tor-guardmgr`, the vendored `tor-dirmgr`, and other tor crates. Upstream tracks the migration in https://gitlab.torproject.org/tpo/core/arti/-/issues/2882 and ignores the same advisory in its own cargo-audit config. When an Arti release drops humantime, upgrade Arti, update the vendored `tor-dirmgr` and `tor-ptmgr` patches, and remove the waiver.

## Review deadline

Re-evaluate no later than 2027-02-28. The expiry in `native/rust/advisory-waivers.toml` blocks CI on that date until this task is reviewed.

## Acceptance criteria

- [ ] `cargo tree -i humantime --locked --manifest-path native/rust/Cargo.toml` prints nothing.
- [ ] The RUSTSEC-2026-0542 entries are removed from `native/rust/deny.toml` and `native/rust/advisory-waivers.toml`.
- [ ] `cargo deny --locked --manifest-path native/rust/Cargo.toml check` and `python3 scripts/ci/check_rust_advisory_waivers.py` pass.
- [ ] `cargo test -p ripdpi-tor --locked` passes.
