# RST-1786264762917563: Replace unmaintained bincode transitive dependency

## Objective

Remove the locked bincode 2.0.1 path and its expiring advisory waiver without regressing Tor directory behavior.

## Ownership

- serialized native dependency graph, lockfile, waiver, and focused Tor tests

## Execution

- [ ] RST-1786264762919478 Remove bincode 2.0.1 from the locked graph, preserve Tor behavior, and delete the waiver #chore !low @item:RST-1786264762917563

## Verification

- locked metadata assertion, focused Tor tests, cargo-deny advisories, and waiver validator
