# ripdpi-diagnostics-parsers

**Role:** support. **Layer:** L6 — diagnostics / monitor.

## Responsibility

Response parsers (HTTP / TLS / SSH) that extract structured fields from probe
responses for downstream classification.

> **No current workspace consumer.** No other crate references this crate in
> `[dependencies]` / `[dev-dependencies]` — verify whether it is feature-gated,
> test-only, or pending integration before extending it. See
> [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md)
> § Open verification items.

## Main dependencies

`ripdpi-failure-classifier`; `hickory-proto`, `serde`.

## Extension points

A new response parser — once a consumer is confirmed.

## What must not be added here

Network probing or I/O — this crate only *parses* bytes already collected.

---
See [`DIAGNOSTICS_ARCHITECTURE.md`](../../../../docs/architecture/DIAGNOSTICS_ARCHITECTURE.md)
for the scan pipeline and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§3 for adding a probe.
