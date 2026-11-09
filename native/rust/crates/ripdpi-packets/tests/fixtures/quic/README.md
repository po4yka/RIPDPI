# QUIC Initial wire vectors

The six base files contain the protected packets from RFC 9001 Appendices A.2–A.4 (v1)
and RFC 9369 Appendices A.2–A.4 (v2). They are independent test inputs for native
validation and Android JNI tests. They contain public example data.

- https://www.rfc-editor.org/rfc/rfc9001.html#appendix-A
- https://www.rfc-editor.org/rfc/rfc9369.html#appendix-A

The bytes are the hexadecimal packet listings, with whitespace removed. No
RIPDPI builder generated these packets.

## Derived large responses

`rfc9001-server-initial-3000.bin` and `rfc9369-server-initial-3000.bin` are
3000-byte server Initial packets, not verbatim RFC vectors. Their plaintext is
the matching RFC server Initial plaintext followed by PADDING. They retain the
RFC connection IDs, use packet number zero with four encoded bytes, and have
updated Length, AEAD and header protection. The known client Initial remains
the request used for response correlation.

The independent producer used pinned rustls 0.23.46 ring QUIC keys and its
header/packet protection APIs. It did not use a RIPDPI packet builder, key
derivation or encryption routine. The producer checked full native acceptance
and rejection of the same packet truncated to 2048 bytes. Its temporary source
and log are `/tmp/std08-large-initial-oracle/generator.rs` and
`/tmp/std08-large-initial-oracle-generator.log`.

- Generator SHA-256: `115e10902f2b1f60883ac6385636d137d307baa34d9da7a793d91e1474f4ab16`.
- `rfc9001-server-initial-3000.bin` SHA-256: `2a512bef5b06f73fa130eec9ef8cb833bc4c03b0d8c9f5cd05668924efd59904`.
- `rfc9369-server-initial-3000.bin` SHA-256: `4466ad42ca49a1e08465fd70dfac5c0024b53226505d9b276d338fadb37c6708`.

TShark 4.6.9 independently decoded both derived captures. Each response UDP
length is 3008 (3000 bytes of QUIC), and each exposes TLS ServerHello type 2.
No malformed/decryption error was reported; the TLS legacy_version note is
informational. Captures and field output are under
`/tmp/std08-large-initial-oracle/rfc9001-large.pcap`, `rfc9369-large.pcap`,
`rfc9001-tshark.txt`, and `rfc9369-tshark.txt`.
