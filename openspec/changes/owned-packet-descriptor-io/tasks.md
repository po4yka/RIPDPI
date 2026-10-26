# VPN-1793035703372515

## Objective

Restore raw packet I/O and all existing TUN E2E behavior on the updated dependency set.

## Ownership

Dependency integration owns native/rust/crates/ripdpi-tunnel-core, native/rust/Cargo.lock, this change, and the linked portfolio record. Review agents are read-only.

## Execution

- [ ] VPN-1793035899141801 Implement owned packet I/O and verify byte framing, readiness, ownership, and TUN E2E gates #bug !high @item:VPN-1793035703372515

## Verification

Run the complete tunnel-core test suite and Clippy through build-gate. Check formatting, architecture, and task contracts. Require successful hosted CI with Rust coverage and Android ABI builds before integration.
