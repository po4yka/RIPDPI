# ripdpi-privileged-ops

**Responsibility:** the privileged network primitives — raw IPv4/IPv6 sockets,
`TCP_REPAIR`, IP fragmentation, fake-TCP / fake-RST emission, TTL operations,
and the privileged capability checks — the operations the root helper executes.
**Layer:** L5 — platform / privileged.

## Stable identifiers / contracts

The privileged-operation function signatures are the contract the
`ripdpi-root-helper` binary (and, for the local-fallback path,
`ripdpi-runtime-platform`) call into. `TCP_REPAIR`-class operations may return
a replacement fd, swapped in by the caller via `dup2()`.

## Dependency direction

**Upstream:** `ripdpi-capabilities`, `ripdpi-config`, `ripdpi-desync`,
`ripdpi-ipfrag`. **Downstream:** `ripdpi-root-helper`, `ripdpi-runtime-platform`.

## Non-root fallback

These primitives **require privilege** to succeed. They never assume root —
callers (`ripdpi-runtime-platform` via `with_root_helper()`) gate them on the
capability set and supply a non-privileged fallback or an inert outcome when
root is absent. A primitive that hard-fails the runtime when unprivileged is a
bug against the non-root baseline.

## Extension checklist

1. Add the privileged primitive here; gate it on a `ripdpi-capabilities` flag.
2. Wire a `CMD_*` for it in `ripdpi-root-helper-protocol` and a handler in
   `ripdpi-root-helper`.
3. Ensure `ripdpi-runtime-platform` exposes a non-privileged fallback.
4. `unsafe` raw-socket / ioctl code carries a `// SAFETY:` block per the
   `rust-unsafe` discipline.

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md),
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md),
and [`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md) §7.
