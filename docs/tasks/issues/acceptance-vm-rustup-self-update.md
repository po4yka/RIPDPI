---
id: TST-1791562210531707
title: Keep acceptance VM rustup owned by its package manager
kind: bug
status: review
area: testing
priority: high
owner: linux-acceptance
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-09
updated: 2026-10-09
spec_reason: tooling-only
status_detail: Real VM rustup failure reproduced;23 VM tests green after red regression; pinned install succeeds with --no-self-update; full clean packet preparation pending serialized gate
---

## Goal

Prepare the acceptance packet engine with the repository-pinned Rust toolchain without changing the package-managed rustup binary. Coordinated by TST-1791553917096956.

## Acceptance criteria

- Preserve the Rust 1.98.1 pin and locked Cargo commands.
- Disable rustup self-update during toolchain installation.
- Observe the existing VM failure, a failing regression, the passing regression, and a fresh successful VM preparation.
- Run the full VM unit suite and shell syntax checks. Independent review must pass before commit.

## Evidence

The clean 83c103c2b preparation failed before Cargo at `build/acceptance/packet-prepare-83c103c2b.private.log`: rustup could not set permissions on absent `/var/cache/ripdpi-acceptance/cargo/bin/rustup-init`. The failed guard receipt is `/tmp/ripdpi-acceptance-20261009-linux/packet-budget-prepare-83c103c2b.json`. These files remain intact. Native30 and routed9 at that SHA passed, but this failure is not packet acceptance.

The regression failed with the prior command (exit 42), then all 23 VM tests passed with the fix. `bash -n` and `git diff --check` passed. The exact pinned toolchain install now succeeds in the owned VM; output is `build/acceptance/vm-rustup-real-green.private.log`. Full clean-source packet preparation and runtime will run after integration and compiler serialization. The attempted dirty-source full preparation remained queued at build-gate and was canceled before VM execution; it is not a successful preparation.
