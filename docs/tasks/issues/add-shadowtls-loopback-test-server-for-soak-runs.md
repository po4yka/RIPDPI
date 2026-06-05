---
title: Add ShadowTLS loopback test server for soak runs
type: task
status: todo
area: testing
priority: low
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-06-05
---

## Summary

`ripdpi-shadowtls` only ships a client. Add a minimal loopback test-server (under `tests/` or behind a `test-server` feature) so soak tests, fuzz harnesses, and future server-side conformance work have a controlled counterpart.

## Context

Without an in-tree server, every test path that needs a peer must rely on a real upstream or skip. A small reference server also makes round-trip golden capture trivial.

## Acceptance criteria

- [ ] A `test-server` feature compiles a minimal HKDF/HMAC handshake server.
- [ ] At least one soak test (`#[ignore]` by default) drives N handshakes back-to-back through the test server and asserts no leak.
- [ ] The README notes that this server is **not** a production implementation.

## Definition of done

- Test server compiles and is exercised by at least one soak case.

## Links

- [[add-shadowtls-v2-compatibility-or-document-v3-only]]

## Work log

- 2026-06-05: No test-server feature, no soak tests, and no server code in native/rust/crates/ripdpi-shadowtls/; SPEC.md explicitly notes the absence. All three acceptance criteria remain unmet.
