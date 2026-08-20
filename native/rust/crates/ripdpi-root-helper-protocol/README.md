# ripdpi-root-helper-protocol

**Responsibility:** the root-helper IPC wire protocol — the `CMD_*` command
constants, request/response parameter types, and the `SCM_RIGHTS` fd-passing
helpers shared by the privileged helper binary and its in-app client.
**Layer:** L5 — platform / privileged.

## Stable identifiers / contracts

This crate **is** the root-helper contract. The `CMD_*` string constants in
`src/commands.rs` (`v3/protocol_preflight`, `v3/probe_capabilities`, `v3/send_fake_rst`,
`v3/send_seqovl_tcp`, `v3/send_multi_disorder_tcp`, `v3/send_ip_fragmented_tcp/udp`,
`v3/send_raw_ip_packet`, `v3/shutdown`, …) are a **frozen wire contract**. The
version namespace makes an older helper reject newer privileged operations
before dispatch, even if the helper changes after the preflight.
The helper binary and the client must change in lock-step. Full contract:
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md).

## Dependency direction

**Upstream:** none (leaf crate). **Downstream:** `ripdpi-root-helper` (the
binary) and `ripdpi-runtime-platform` (the client). Both ends depend on this
one crate so the protocol cannot drift.

## Non-root fallback

This crate only defines the protocol; it performs no privilege itself. The
fallback logic lives in `ripdpi-runtime-platform`'s `with_root_helper()`
dispatch — every command must have a non-privileged fallback or be inert when
no helper is registered.

## Extension checklist

1. Add a `CMD_*` constant + its request/response param types — never rename an
   existing one.
2. Document the JSON request/response shape inline (as the existing constants do).
3. Update both the helper handler and the client in the same change.

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md),
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md),
and [`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md) §7.
