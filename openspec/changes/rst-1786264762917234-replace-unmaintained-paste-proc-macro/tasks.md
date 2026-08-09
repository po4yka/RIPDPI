# RST-1786264762917234: Replace unmaintained paste proc-macro dependency

## Objective

Eliminate every paste 1.0.15 path and its expiring advisory waiver.

## Ownership

- serialized native dependency graph, netlink/Arti consumers, lockfile, and advisory waiver

## Execution

- [ ] RST-1786264762919747 Remove paste 1.0.15 from all locked paths and delete the waiver #chore !low @item:RST-1786264762917234

## Verification

- reverse-dependency assertion, focused netlink/Tor tests, cargo-deny advisories, and waiver validator
