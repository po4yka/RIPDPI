---
id: RST-1786264762917563
title: Replace unmaintained bincode transitive dependency
kind: chore
status: backlog
area: rust-native
priority: low
risk: high
owner: Native security maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: rst-1786264762917563-replace-unmaintained-bincode-transitive-dependency
created: 2026-08-04
updated: 2026-08-09
---

## Goal

Remove the `RUSTSEC-2025-0141` waiver by upgrading or replacing the Arti `tor-netdir` to `typed-index-collections` path that still pulls `bincode 2.0.1`.

## Review deadline

Re-evaluate the waiver no later than 2026-11-02. The machine-checked expiry in `native/rust/advisory-waivers.toml` intentionally blocks CI on that date until this task is reviewed.

## Acceptance criteria

- [ ] `cargo metadata --locked --format-version 1 --manifest-path native/rust/Cargo.toml | jq -e '[.packages[] | select(.name == "bincode" and .version == "2.0.1")] | length == 0'` passes.
- [ ] The Tor relay retains equivalent directory and typed-index behavior after the upstream dependency change.
- [ ] `RUSTSEC-2025-0141` is removed from `native/rust/deny.toml` and `native/rust/advisory-waivers.toml`.
- [ ] `cargo deny --locked --manifest-path native/rust/Cargo.toml check advisories` and `python3 scripts/ci/check_rust_advisory_waivers.py` pass.
