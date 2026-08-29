---
id: RST-1786264762917942
title: Replace unmaintained daemonize CLI dependency
kind: feature
status: done
area: rust-native
priority: low
owner: Dependency hygiene lane
parent: null
blocked_by: []
spec_mode: required
openspec_change: rst-1786264762917942-replace-unmaintained-daemonize-cli-dependency
created: 2026-07-13
updated: 2026-08-29
closed_at: "2026-08-29T13:49:14Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: daemonize and its advisory waiver are absent; real daemon/PID-file lifecycle, cargo-deny, advisory and workspace Clippy gates passed; exact-SHA CI 33251657196 passed.
---

## Goal

Remove the `RUSTSEC-2025-0069` waiver by replacing `daemonize 0.5.0` in the local `ripdpi-cli` process mode while keeping the dependency outside every Android runtime graph.

## Review deadline

Re-evaluate the waiver no later than 2026-10-11. The machine-checked expiry in `native/rust/advisory-waivers.toml` intentionally blocks CI on that date until this task is reviewed.

## Acceptance criteria

- `cargo tree --manifest-path native/rust/Cargo.toml -p ripdpi-android -i daemonize` remains empty.
- The local CLI retains equivalent opt-in daemonization and PID-file behavior without `daemonize 0.5.0`.
- `RUSTSEC-2025-0069` is removed from `native/rust/deny.toml` and `native/rust/advisory-waivers.toml`.
- `cargo deny --manifest-path native/rust/Cargo.toml check advisories` and `python3 scripts/ci/check_rust_advisory_waivers.py` pass.
