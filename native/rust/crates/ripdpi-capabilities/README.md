# ripdpi-capabilities

**Responsibility:** the device-capability model — the typed representation of
which privileged operations the current device and root helper support
(raw IPv4, raw IPv6, `TCP_REPAIR`, …).
**Layer:** L5 — platform / privileged.

## Stable identifiers / contracts

The capability types are the gate used across the privileged path. They
correspond to the `probe_capabilities` response shape
(`{ raw_ipv4, raw_ipv6, tcp_repair }`) defined in
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md).

## Dependency direction

**Upstream:** none (leaf crate). **Downstream:** `ripdpi-privileged-ops`,
`ripdpi-runtime-platform` — both gate privileged operations on these types.

## Non-root fallback

This crate **models** capability — it is the mechanism by which the non-root
baseline is enforced. On a non-rooted device (or when the helper fails to
start) capabilities resolve to "unavailable", and the gating logic disables the
dependent privileged operations cleanly. Capability checks decide whether an
emitter runs; they do not change the tactic taxonomy.

## Extension checklist

1. Add a capability flag here.
2. Surface it in the `probe_capabilities` response
   (`ripdpi-root-helper-protocol` / `ripdpi-root-helper`).
3. Gate the dependent privileged operation in `ripdpi-privileged-ops` /
   `ripdpi-runtime-platform` on the new flag.

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md),
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md),
and [`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md) §7.
