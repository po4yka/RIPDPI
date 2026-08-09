# RST-1786264762917099: Remove unconsumed protocol-detect and diagnostics-parsers crates

## Objective

Delete the two currently unconsumed native crates and leave the locked workspace and architecture map consistent.

## Ownership

- `native/rust/crates/ripdpi-protocol-detect/**`
- `native/rust/crates/ripdpi-diagnostics-parsers/**`
- serialized `native/rust/Cargo.toml` and `Cargo.lock` lane
- `docs/architecture/NATIVE_RUST.md`

## Execution

- [ ] RST-1786264762919395 Reconfirm both crates have no runtime, dev, harness, or build consumer #chore !low @item:RST-1786264762917099
- [ ] RST-1786264762919083 Remove both crates and update workspace manifests, lockfile, and NATIVE_RUST.md #chore !low @item:RST-1786264762917099 @blocked_by:RST-1786264762919395
- [ ] RST-1786264762919718 Run locked metadata, architecture contracts, tests, and cargo-deny after removal #chore !low @item:RST-1786264762917099 @blocked_by:RST-1786264762919083

## Verification

- `cargo metadata --locked`
- `python3 scripts/ci/check_native_architecture_contracts.py`
- focused native tests plus `cargo deny check`
