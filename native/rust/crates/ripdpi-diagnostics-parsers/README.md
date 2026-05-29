# ripdpi-diagnostics-parsers

**Role:** support. **Layer:** L6 — diagnostics / monitor.

## Responsibility

Response parsers for HTTP responses and DNS packets that extract structured
fields from probe responses for downstream classification and fuzz coverage.

> **No production workspace consumer.** No other crate references this crate in `[dependencies]` / `[dev-dependencies]`; the only consumer is `native/rust/fuzz` (the `monitor_dns_response` and `monitor_http_response` fuzz targets). Treat it as a prune candidate unless parser extraction is revived.

## Main dependencies

`ripdpi-failure-classifier`; `hickory-proto`, `serde`.

## Extension points

A new response parser — only after a consumer is confirmed.

## What must not be added here

Network probing or I/O — this crate only *parses* bytes already collected.

---
See [`DIAGNOSTICS_ARCHITECTURE.md`](../../../../docs/architecture/DIAGNOSTICS_ARCHITECTURE.md)
for the scan pipeline and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§3 for adding a probe.
