# RST-1786264762917304: Remove RSA advisory paths from russh and Arti dependencies

## Objective

Remove every vulnerable RSA dependency path and its waiver while preserving SSH and Tor behavior.

## Ownership

- serialized russh/Arti dependency graph, lockfile, waiver, and focused SSH/Tor tests

## Execution

- [ ] RST-1786264762919282 Remove vulnerable RSA paths from russh and Arti and delete RUSTSEC-2023-0071 waivers #chore !low @item:RST-1786264762917304
- [ ] RST-1786264762919454 Verify focused SSH and Tor behavior on the locked replacement graph #chore !low @item:RST-1786264762917304 @blocked_by:RST-1786264762919282
- [ ] RST-1786264762919575 Run the locked native workspace and advisory gates #chore !low @item:RST-1786264762917304 @blocked_by:RST-1786264762919454

## Verification

- `cargo nextest run -p ripdpi-ssh --locked` and focused Arti tests
- locked workspace tests, cargo-deny advisories, and waiver validator
