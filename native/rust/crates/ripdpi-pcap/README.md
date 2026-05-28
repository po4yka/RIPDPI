# ripdpi-pcap

**Layer:** L1 -- protocol / core.

`ripdpi-pcap` provides classic pcap read/write support and endpoint redaction for RIPDPI packet-capture export paths.

## Boundaries

- File-format I/O and redaction helpers belong here.
- Android export UI, consent, and storage routing belong in Kotlin or Android adapter crates.

## Checks

Run focused checks with `cargo test -p ripdpi-pcap`.
