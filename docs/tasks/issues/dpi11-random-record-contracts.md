---
id: RST-1794557083402232
title: Enforce TLS random-record validation and envelope contracts
kind: bug
status: review
area: rust-native
priority: high
owner: dpi11-audit
parent: null
blocked_by: []
spec_mode: required
openspec_change: dpi11-random-record-contracts
created: 2026-11-13
updated: 2026-11-13
---

## Goal

Enforce the existing DPI-11 configuration and TLS framing safety contracts.

## Acceptance criteria

- Native primary and rotation chains reject positive values outside count 2..16 and size 1..4096, including reversed bounds, after default normalization.
- TLS preludes leave non-handshake records unchanged and preserve valid and truncated-fake behavior.
- Tail partition arithmetic does not overflow for representable direct API inputs.
- Adaptive profiles keep each tail record within the saved minimum and maximum.
- Owning regression tests, source gates, and independent review pass before integration.

## Ownership

- config_bounds: ripdpi-proxy-config/src/convert/chain/tcp/tlsrandrec.rs, src/tests.rs, src/tests_tlsrandrec.rs.
- tls_envelope: ripdpi-desync/src/first_flight_ir.rs, the tlsminor guard in apply_prelude, and src/tests/tls_prelude.rs; optional existing first_flight_ir tests.
- runtime_bounds: only resolve_tlsrandrec_fragment_sizes in ripdpi-desync/src/tls_prelude.rs, src/offset.rs, src/tests/offset.rs, and a new owning runtime-bounds test module.
- dpi11-audit: task records, OpenSpec artifacts, generated board, integration, and combined validation.
- pr-reviewer: read-only review. Each writer uses a separate worktree. No writer changes lockfiles, manifests, locales, baselines, or golden fixtures.
