# ADR-006: Tier-3 Desync Platform Primitives

**Status:** Accepted  
**Date:** 2026-04-27  
**Author:** cleanup/roadmap-2026-04 (P3.2)

---

## Context

Two evasion techniques are fully implemented at the platform layer:

- **SYN hide** (`send_syn_hide_tcp`) — sends a crafted non-SYN TCP packet whose
  header carries a marker value in one of three positions (reserved bit X2,
  urgent pointer, or TCP timestamp echo). Useful for probing whether a DPI box
  tracks connection state or only inspects the SYN packet.
- **ICMP-wrapped UDP** (`send_icmp_wrapped_udp` / `recv_icmp_wrapped_udp`) —
  tunnels arbitrary UDP payloads inside ICMP echo/reply packets using a custom
  framing format (`RDP1` magic, version byte, session ID, optional XOR
  obfuscation). Provides a data-exfiltration path that bypasses UDP-blocking
  firewalls.

Both techniques exist in two crates:

| Crate | Role |
|---|---|
| `ripdpi-privileged-ops` | Low-level send/recv; invoked by the root-helper binary (`main.rs`) via IPC command strings (`CMD_SEND_SYN_HIDE_TCP`, etc.) |
| `ripdpi-runtime` | Higher-level wrapper; dispatches to root-helper IPC when available, falls back to direct Linux raw-socket calls otherwise |

The IPC plumbing in `ripdpi-privileged-ops` is complete end-to-end:

- `src/experimental_tier3.rs` — data structures + platform functions
- `src/linux/experimental_tier3.rs` — Linux raw-socket implementation
- `src/handlers.rs` — `handle_send_syn_hide_tcp`, `handle_send_icmp_wrapped_udp`,
  `handle_recv_icmp_wrapped_udp`
- `src/main.rs` — command dispatch (`CMD_SEND_SYN_HIDE_TCP` → handler)
- `src/lib.rs` — `pub use` re-exports
- `root_helper_client.rs` — typed client methods in `ripdpi-runtime`

**What is missing:** no `DesyncMode` variant routes to these functions. The
`DesyncMode` enum in `ripdpi-config` has no `SynHide` or `IcmpWrappedUdp`
member. The Kotlin `app_settings.proto` has no corresponding field. The
Android UI has no option to enable either technique. Callers who want to
invoke these primitives must construct the spec structs and call the functions
directly — they are not reachable via the standard strategy/chain pipeline.

Additionally, the three public send/recv functions in `ripdpi-runtime`'s
`platform/mod.rs` are re-exported with `pub` visibility, making them part
of the crate's external API despite having no external callers today.

---

## Decision

**Variant C: document and retain as internal platform primitives.**

Reasons:

1. **The platform layer is complete.** The root-helper IPC path is fully
   implemented and tested (unit tests in both crates cover envelope
   encoding/decoding, packet construction, and extraction). Deleting tested,
   correct code that already costs maintenance budget to build is wasteful.

2. **Wire-up is a separate, larger epic.** Connecting these primitives to
   `DesyncMode` requires: a new enum variant in `ripdpi-config`, a
   `app_settings.proto` schema migration, a `DesyncMode` proto field addition,
   UI fields in the Mode Editor, packet-smoke test scenarios for both
   techniques, and a security review (SYN hide behavior varies across kernel
   versions and some Android vendors strip reserved TCP bits). This is Phase 4
   scope, not a single Phase 3 cleanup commit.

3. **Deleting would remove the only root-helper IPC path for these opcodes.**
   The command strings `CMD_SEND_SYN_HIDE_TCP`, `CMD_SEND_ICMP_WRAPPED_UDP`,
   and `CMD_RECV_ICMP_WRAPPED_UDP` are already part of the IPC contract. Their
   handlers live in the root-helper binary. Removing them would create a
   silent gap between the binary's command set and the runtime client.

4. **No API stability guarantee is implied.** These functions are used only
   within the `ripdpi-runtime` crate internally (via `root_helper_client`).
   Narrowing their visibility to `pub(crate)` at the `platform/mod.rs`
   re-export boundary correctly reflects this.

---

## Changes Made (P3.2)

1. **`ripdpi-runtime/src/platform/experimental_tier3.rs`** — added module-level
   doc comment declaring status (internal primitive, not wired through
   `DesyncMode`) with a reference to this ADR. Public function signatures
   are unchanged; visibility tightening happens at the re-export boundary.

2. **`ripdpi-runtime/src/platform/mod.rs`** — changed re-export of
   `send_syn_hide_tcp`, `send_icmp_wrapped_udp`, `recv_icmp_wrapped_udp`,
   and their associated types from `pub use` to `pub(crate) use`. This removes
   them from the crate's public API while preserving access for
   `root_helper_client.rs` (same crate).

3. **`ripdpi-privileged-ops/src/experimental_tier3.rs`** — added module-level
   doc comment declaring status, consistent with the runtime crate. The
   `pub fn` signatures here remain `pub` because the root-helper binary's
   `handlers.rs` and `main.rs` live in the same crate and call them via
   `pub use` from `lib.rs`.

4. **This ADR** — created to record the rationale and the conditions for
   future activation.

---

## Consequences

### Immediate

- `ripdpi-runtime`'s public API no longer includes `send_syn_hide_tcp`,
  `send_icmp_wrapped_udp`, `recv_icmp_wrapped_udp`, or their spec/filter
  types. Any hypothetical external caller would need to use
  `root_helper_client` methods instead (which is the correct path anyway).
- The `ripdpi-privileged-ops` public surface is unchanged; the root-helper
  binary continues to dispatch all three IPC commands correctly.
- No proto migration, no UI changes, no packet-smoke additions in this commit.

### Future wire-up (Phase 4 epic — not in P3.2 scope)

For activation through the standard strategy pipeline the following work is
required:

1. **`ripdpi-config`** — add `DesyncMode::SynHide` and
   `DesyncMode::IcmpWrappedUdp` enum variants; define associated parameter
   structs (`SynHideParams`, `IcmpWrappedUdpParams`) derived from the existing
   spec types.

2. **`app_settings.proto`** — add `SYN_HIDE` and `ICMP_WRAPPED_UDP` to the
   `DesyncMode` proto enum; add a `SynHideConfig` and `IcmpWrappedUdpConfig`
   message; add migration in the proto datastore layer.

3. **Engine dispatch** — add `DesyncMode::SynHide` and
   `DesyncMode::IcmpWrappedUdp` match arms wherever `DesyncMode` is exhaustively
   matched (plan_tcp, execute, emit paths).

4. **UI** — add Mode Editor fields for `marker_kind`, `marker_value`, TTL,
   session ID, ICMP code, and XOR flag; add validation rules.

5. **packet-smoke** — add scenario fixtures for SYN-hide and ICMP-wrapped UDP
   send/recv round-trips on the Android emulator.

6. **Security review** — verify SYN-hide reserved-bit behavior on Android 12+
   (some vendor kernels zero reserved TCP bits before forwarding); verify ICMP
   raw-socket availability on non-root Android with VPN protect callback.

Wire-up MUST NOT proceed without completing all six items above.
