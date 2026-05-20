# ripdpi-diagnostics-net

**Role:** aggregation facade (compat). **Layer:** L6 — diagnostics / monitor.

## Responsibility

A compatibility facade over the per-protocol probe crates. Its dependency set
mirrors `ripdpi-diagnostics-protocols`.

> **No current workspace consumer.** No other crate's `[dependencies]` or
> `[dev-dependencies]` references this crate. It appears to be superseded by
> `ripdpi-diagnostics-protocols` — verify its intended role before extending
> it. See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md)
> § Open verification items.

## Main dependencies

`ripdpi-diagnostics-contracts` and the per-protocol probe crates
(`ripdpi-diagnostics-{dns,fat-header,http,telegram,tls,transport}`).

## Extension points

None until the supersession question above is resolved — prefer
`ripdpi-diagnostics-protocols`.

## What must not be added here

New probe logic. Treat this as a compat shim pending confirmation, not an
active extension surface.

---
See [`DIAGNOSTICS_ARCHITECTURE.md`](../../../../docs/architecture/DIAGNOSTICS_ARCHITECTURE.md)
for the scan pipeline and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§3 for adding a probe.
