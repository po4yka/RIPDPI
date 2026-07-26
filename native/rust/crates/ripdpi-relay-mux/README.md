# ripdpi-relay-mux

**Responsibility:** reusable relay-session pooling and stream-lease management
for multiplexed transports.
**Layer:** L7 — relay transports.

Provides the connection pool, stream leases, health tracking, and the mux wire
state shared by transports that multiplex streams over a reused connection.

## Stable identifiers / contracts

The pool / lease / health contracts (`contracts.rs`) and the mux wire state are
the surface consumed by `ripdpi-relay-core` and `ripdpi-vless`.

## Dependency direction

**Upstream:** none (leaf crate). **Downstream:** `ripdpi-relay-core`,
`ripdpi-relay-tls-transports`, and `ripdpi-vless`.

## Non-root fallback

No privileged operations — runs fully on non-rooted devices. See
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md).

## Extension checklist

1. Add a new pooling or stream-lease policy behind the existing contracts.
2. Keep wire-mux state changes backward-compatible; golden tests cover it.

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md),
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md),
and [`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md).
