# Change: Replace BoundedHeap with the standard BinaryHeap

Task ID: `RST-1794036632883567`

## Why

UDP association eviction must use the Rust standard heap without changing bounded history or stale-entry handling. The 2026-11-07 audit identifies this as finding 11 (attachment 1) at source revision 202f970.

## What Changes

- Use BinaryHeap<Reverse<UdpEvictionEntry>> and remove the unused ripdpi-collections crate, its extra operations, and crate wiring after confirming all consumers.
- When an association must be evicted, the same oldest eligible association is selected.
- Stale entries and reused source addresses cannot evict a newer association generation.
- Correct only the specified defect or infrastructure duplication. No other breaking change is planned; Keep the explicit 2048-entry threshold, heap rebuilding, association ID/generation checks, eviction order, and bounded activity history. Capacity is not a size limit.

## Capabilities

### New Capabilities

- `standard-components/replace-bounded-heap-with-binary-heap`: UDP association eviction must use the Rust standard heap without changing bounded history or stale-entry handling.

### Modified Capabilities

- None. This delta records the previously unspecified contract for the audited path.

## Impact

- `native/rust/crates/ripdpi-collections`
- `native/rust/crates/ripdpi-tunnel-core/src/io_loop/udp_assoc/eviction.rs`
- `native/rust/crates/ripdpi-tunnel-core/src/io_loop/udp_assoc/tests.rs`

Use only existing platform or workspace libraries. Check the direct module dependency before editing. The parent epic owns programme order and shared lanes. No external publication, deployment, or unrelated protocol change is included.
