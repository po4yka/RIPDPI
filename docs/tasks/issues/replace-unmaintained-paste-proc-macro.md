---
id: RST-1786264762917234
title: Replace unmaintained paste proc-macro dependency
kind: chore
status: backlog
area: rust-native
priority: low
owner: Native security maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: rst-1786264762917234-replace-unmaintained-paste-proc-macro
created: 2026-07-13
updated: 2026-08-09
---

## Goal

Remove the `RUSTSEC-2024-0436` waiver by eliminating every locked `paste 1.0.15` path, including current netlink and Arti `pwd-grp`/`fs-mistrust` consumers.

## Review deadline

Re-evaluate the waiver no later than 2026-10-11. The machine-checked expiry in `native/rust/advisory-waivers.toml` intentionally blocks CI on that date until this task is reviewed.

## Acceptance criteria

- [ ] `cargo tree --manifest-path native/rust/Cargo.toml -i paste` no longer reports `paste 1.0.15` through any dependency family.
- [ ] `RUSTSEC-2024-0436` is removed from `native/rust/deny.toml` and `native/rust/advisory-waivers.toml`.
- [ ] `cargo deny --manifest-path native/rust/Cargo.toml check advisories` and `python3 scripts/ci/check_rust_advisory_waivers.py` pass.
