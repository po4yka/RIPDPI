---
id: TST-1794128447029776
title: Bound delayed socket replacement fixture readiness
kind: bug
status: done
area: testing
priority: medium
owner: tcp-fixture
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-08
updated: 2026-11-08
spec_reason: test-only
closed_at: "2026-11-08T09:08:02Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Accept readiness fixture fixed at cbedaf8a833707c59ef3b2f1cd5fe338cee8589a. Baseline without P2-01 reproduced 348/1 with correct new socket, selected group and seed before WouldBlock. Narrow test 1/1; isolated worktree full proxy 349/349 and combined tree 351/351. Async cancellation audit, strict all-target clippy, task contracts and just ci-gates passed.
---

## Goal

Make the existing delayed socket replacement test stable under the full parallel library suite. Preserve the different physical source address, selected group, and exact seed payload checks. Do not change production routing or relax the test oracle.

## Acceptance criteria

- Capture the selected group, seed payload, and physical local address on the existing failure before choosing a repair.
- Reproduce the failure without the LOC-11 reconnect production fix.
- Repair only the proved fixture timing assumption, with bounded waits and no serial test gate.
- The narrow fixture and full parallel proxy library suite pass.

## Ownership

The tcp-fixture writer owns only `native/rust/crates/ripdpi-proxy-runtime/src/runtime/handshake/connect_relay/tcp_reuse_tests.rs` and this test-only task in this worktree. The integration owner changes the accounting worktree separately. Dependency, schema, baseline, and golden files stay unchanged.

## Evidence

Base `a240897b6d49d9ede90d144ba851447dbf87223b` contains direct-path admission fix P2-02 but not the P2-01 reconnect repair. The other worktree observed this test failing at the second nonblocking accept in two full library runs, while the isolated test passed.

The diagnostic full-library run failed on the baseline with selected group 0, the exact HTTP request seed, and a replacement local address different from the provisional source. The immediate nonblocking accept returned `WouldBlock`. The fixture now waits for listener readiness with the existing Tokio test runtime and a three-second timeout; it does not delay blindly or serialize the suite. Evidence: `/tmp/loc11-fix-tcp-fixture/baseline-diagnostic.log`.

Observed verification after the fixture repair:

- `cargo test -p ripdpi-proxy-runtime --lib --locked changed_pre_connect_receive_buffer_replaces_the_socket`: 1 passed.
- `cargo test -p ripdpi-proxy-runtime --lib --locked`: 349 passed, zero failed or ignored, with the default parallel test runner.
- `cargo clippy -p ripdpi-proxy-runtime --all-targets --locked -- -D warnings`: passed.
- `git diff --check` and `taskctl validate`: passed.
- Logs: `/tmp/loc11-fix-tcp-fixture/narrow-green.log`, `full-green.log`, and `clippy.log`.
