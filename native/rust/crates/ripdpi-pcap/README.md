# ripdpi-pcap

**Layer:** L1 -- protocol / core.

`ripdpi-pcap` provides classic pcap read/write support and redaction for RIPDPI packet-capture export paths. The redacted export clears endpoint addresses, unknown option values, and packet payloads. Timing, sizes, ports, fixed header fields, and the IPv6 Jumbo Payload length remain visible.

## Boundaries

- File-format I/O and redaction helpers belong here.
- Android export UI, consent, and storage routing belong in Kotlin or Android adapter crates.

## Checks

Run focused checks with `cargo test -p ripdpi-pcap`.
