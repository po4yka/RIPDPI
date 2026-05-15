# QUIC-Common Extraction Audit

> Status: **audit complete** — recommendation: **do not extract a new crate; tighten the existing re-export surface instead**.
> Authored: 2026-05-15.
> Tracking task: `docs/tasks/issues/audit-quic-common-extraction-from-masque-and-hysteria2.md`.

## Question

Should the QUIC-common bits currently shared between `ripdpi-hysteria2`,
`ripdpi-masque`, and `ripdpi-tuic` move into a neutral
`ripdpi-quic-common` crate?

## What is shared today

`ripdpi-hysteria2::quic_transport` re-exports:

| Symbol | Used by |
|---|---|
| `build_client_udp_socket` | hysteria2 (direct), tuic (reimplemented in `endpoint.rs`) |
| `build_quic_endpoint` | hysteria2, masque (via dep) |
| `maybe_rebind_endpoint`, `rebind_endpoint` | hysteria2 migration, masque migration |
| `H3ClientParts`, `H3Transport`, `H3ConnectKind` | hysteria2, masque |
| `QuicBiStream`, `QuicDatagramTransport`, `QuicTransport`, `QuicTransportConfig` | hysteria2, masque |

`ripdpi-masque/Cargo.toml` carries `ripdpi-hysteria2 = { workspace = true }`
specifically for these symbols. `ripdpi-tuic/src/endpoint.rs` has a
private `build_client_udp_socket` that mirrors the hysteria2 shape
without sharing.

## What is *not* shared but easily confused with shared

- TUIC's `ClientSocketSpec` shape mirrors Hysteria's but the structs are
  defined independently. Aligning them only matters if a shared crate
  comes along.
- Salamander (Hysteria-only) and port-hopping (Hysteria-only) sit in
  the same crate as the would-be neutral utilities; extraction would
  have to split that crate.

## Dependency edges today

```
ripdpi-masque ─► ripdpi-hysteria2 (transitive: salamander, port-hopping pulled in)
ripdpi-tuic   ─► (none for QUIC utilities; reimplements)
```

The `masque → hysteria2` edge is the one with real coupling cost: a
hysteria2-internal change can ripple into masque's compile graph.

## Estimated diff size and migration risk

| Approach | Files moved | Net code change | Workspace risk |
|---|---|---|---|
| Full extraction to `ripdpi-quic-common` | ~12 files from `quic_transport/` and parts of `socket_spec` | ~1.2-1.6 KLOC moved | Medium-high; touches 3 release-blocking crates |
| Re-export-only narrowing (move public re-exports to a `pub use` shim crate without moving code) | 2 files | < 50 LOC | Low |
| No change | 0 | 0 | Status quo |

## Recommendation: **do not extract**

The shared symbols are real, but their volume is small (~1 KLOC of
QUIC plumbing) and the test surface is mature. Extracting now would:

1. Add a fourth crate-shaped abstraction to an area that already has
   three transport crates plus a shared `quic_transport` submodule.
2. Force a rename pass across `ripdpi-masque` and any downstream
   consumers.
3. Yield little decoupling benefit because the Hysteria-specific
   pieces (Salamander, port-hopping) cannot move with the QUIC
   utilities; they would either stay in `ripdpi-hysteria2` or fragment
   further.

## What to do instead (the de-coupling alternative for MASQUE)

Tighten the public surface of `ripdpi-hysteria2::quic_transport` so
that `ripdpi-masque` depends on a minimal `pub use` set:

1. Move every symbol that MASQUE imports under
   `ripdpi-hysteria2::quic_transport::shared` (or similar) with strict
   `#![deny(missing_docs)]`.
2. Audit `ripdpi-masque` imports to only touch the `shared` namespace.
3. Mark every other `quic_transport::*` re-export `#[doc(hidden)]` or
   `pub(crate)` to make accidental coupling impossible.

If, after one more cycle of upstream watch, the MASQUE↔Hysteria
coupling becomes a measurable burden (e.g. forces a rebuild cascade on
unrelated hysteria changes), revisit this decision. Track that
follow-up under the upstream-watch task; do not pre-extract.

## Owner

This audit's recommendation should be reviewed by the native-runtime
owner before the next release. Open a follow-up only if the
`#[doc(hidden)]` narrowing is rejected or proves insufficient.
