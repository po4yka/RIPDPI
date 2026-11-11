---
id: TRN-1794373625208123
title: Fix lazy sing-mux response handling
kind: bug
status: review
area: transport
priority: high
owner: Transport maintainers
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-11
updated: 2026-11-11
spec_reason: regression-tested-single-module
---

## Goal

Allow native yamux callers to send TCP payload before the sing-mux response. Validate the response on the first read without losing state on cancellation.

## Acceptance criteria

- A client-first exchange completes with the pinned upstream response policy.
- Refused and truncated responses fail without exposing header bytes.
- Cancelling a response read preserves the next payload byte.
- Existing mux tests, affected Rust tests, lint, and CI source gates pass.

## Verification

- The client-first regression failed with a one-second timeout before the fix.
- `cargo test --locked -p ripdpi-vless --lib`: 163 passed before integration.
- `cargo test --locked -p ripdpi-relay-core --lib mux`: 8 passed before integration.
- The fixed session exchanged request and response with original SagerNet sing-mux v0.3.5 serverConn and HashiCorp yamux v0.1.2 on loopback.
- The peer pin is 6fb501d02534177fed5567ee8f63afbc825e2861. The acceptance harness used an isolated copy and no real credentials.
- `just ci-gates` passed before integration. Rust formatting passed.
- `cargo clippy --locked -p ripdpi-vless -p ripdpi-relay-core --all-targets -- -D warnings` passed before integration.
- Independent correctness and cancellation reviews found no confirmed defect.

Refused status and EOF are reported by the first non-empty read. Later reads cannot expose response metadata after validation fails. P2 stream-limit validation remains outside this fix.
