---
title: Vendor fast-socks5 source and consolidate 5 duplicate SOCKS5 implementations
type: task
status: backlog
area: rust-native
priority: high
owner: unassigned
parent: consolidate-rust-manual-implementations-with-vendored-deps
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Vendor fast-socks5 source and consolidate 5 duplicate SOCKS5 implementations #repo/RIPDPI #area/rust-native #status/backlog ⏫

## Summary

Five crates each contain a hand-rolled SOCKS5 client: byte-by-byte CONNECT construction, manual 2-byte auth reply parsing, and independent error handling. Copy the `fast-socks5` source from `/Users/po4yka/GitRep/fast-socks5/` into a new internal crate `ripdpi-socks5-core`, register it in the workspace, and replace all five hand-rolled implementations.

## Affected sites

| Crate | File(s) | Pattern |
|---|---|---|
| `ripdpi-session` | `src/socks5.rs` | Server-side SOCKS5 + SOCKS4 + HTTP CONNECT parser |
| `ripdpi-diagnostics-transport` | `src/transport/socks5.rs` | Synchronous SOCKS5 client for diagnostic probes |
| `ripdpi-dns-resolver` | `src/transport/tcp/socks5.rs` | Async SOCKS5 client for DNS-over-TCP proxying |
| `ripdpi-apps-script-core` | (SOCKS5 connect helper) | SOCKS5 client used by script relay |
| `ripdpi-naiveproxy` | (SOCKS5 tunnel establish) | SOCKS5 client for naiveproxy upstream |

The existing `fast-socks5 = "1.0"` workspace dep in `ripdpi-tunnel-core` becomes a pointer to the internal crate instead.

## Implementation steps

1. Create `native/rust/crates/ripdpi-socks5-core/`.
2. Copy `src/` from `/Users/po4oya/GitRep/fast-socks5/src/` verbatim; copy `Cargo.toml` and strip `[dev-dependencies]` / examples. Rename package to `ripdpi-socks5-core`.
3. Add `ripdpi-socks5-core = { path = "crates/ripdpi-socks5-core" }` to `[workspace.dependencies]`.
4. Remove `fast-socks5 = "1.0"` from `[workspace.dependencies]`.
5. For each affected crate: add `ripdpi-socks5-core` dep, delete the hand-rolled file, wire `ripdpi_socks5_core::client::Socks5Stream` (async) or the sync wrapper for `ripdpi-diagnostics-transport`.
6. `ripdpi-session` server-side: keep existing hand-rolled parser for SOCKS4/HTTP CONNECT (fast-socks5 client-only); only the SOCKS5 client path migrates.

## Acceptance criteria

- [ ] `native/rust/crates/ripdpi-socks5-core/` exists with vendored source; no `fast-socks5` in `[workspace.dependencies]`.
- [ ] All five hand-rolled SOCKS5 client files deleted.
- [ ] `cargo nextest run -p ripdpi-session -p ripdpi-diagnostics-transport -p ripdpi-dns-resolver -p ripdpi-apps-script-core -p ripdpi-naiveproxy` passes.
- [ ] `cargo clippy --workspace` no new warnings.
- [ ] `ripdpi-tunnel-core` resolves `ripdpi-socks5-core` (not `fast-socks5`).
