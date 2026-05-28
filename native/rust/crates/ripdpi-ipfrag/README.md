# ripdpi-ipfrag

**Layer:** L1 -- protocol / core.

`ripdpi-ipfrag` contains IP fragmentation helpers for TCP and UDP packet construction.

## Boundaries

- Packet construction logic belongs here.
- Privileged raw-socket sending belongs in `ripdpi-privileged-ops`; runtime dispatch and root-helper fallback belong in `ripdpi-runtime-platform`.

## Checks

Run focused checks with `cargo test -p ripdpi-ipfrag`.
