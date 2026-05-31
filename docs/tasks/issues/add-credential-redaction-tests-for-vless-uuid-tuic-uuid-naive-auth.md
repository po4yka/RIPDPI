---
title: Add credential redaction tests for VLESS UUID, TUIC UUID, NaiveProxy auth
type: task
status: doing
area: testing
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-31
---

## Summary

Extend the no-secret-logging test surface to specifically cover VLESS UUIDs, TUIC UUIDs, NaiveProxy `Proxy-Authorization` headers, and the WS-tunnel MTProto seed bytes. Tracing macros must never emit these in plaintext.

## Context

add-no-secret-logging-and-diagnostics-redaction-tests (closed task) establishes the general policy. The protocol-specific fields are easy to miss: `VlessRealityConfig.uuid`, `Config` UUIDs in TUIC, the `Proxy-Authorization` basic-auth value in NaiveProxy, and the 64-byte MTProto seed. Each needs a targeted assertion.

## Acceptance criteria

- [x] (partial, 2026-05-15) Manual Debug impls on `VlessRealityConfig` and `ripdpi-tuic::Config` redact UUID, REALITY public key, and password. Unit tests `redacted_debug_omits_uuid_and_reality_key` (vless) and `redacted_debug_omits_uuid_and_password` (tuic) assert the contract. **Remaining work:** capture tracing events directly via a test subscriber to assert no leak in error-path events, and cover NaiveProxy + MTProto seed paths.
- [ ] (original) A per-crate test asserts that `tracing` events emitted on a representative happy-path connect do not contain the UUID or credential as a substring of any captured line.
- [ ] A per-crate test asserts that error events triggered by misconfiguration do not echo the credential.
- [ ] The MTProto seed test asserts that the 64-byte init buffer is never logged in full hex.
- [ ] If a tracing call requires partial visibility (e.g. last 4 bytes), a small `redact_uuid`/`redact_seed` helper centralises the format.

## Definition of done

- Removing the redaction in any one crate fails its targeted test.

## Links

- add-no-secret-logging-and-diagnostics-redaction-tests (closed task)
- [[gate-fake-sni-cert-bypass-behind-allow-insecure-flag-with-telemetry]]
