# QUIC Initial regression vectors

`token-v1.bin` and `token-v2.bin` contain the RFC 9001 A.2 ClientHello,
with DCID `8394c8f03e515708`, SCID `11223344`, token `retry-token`, and
packet number 0. Each UDP payload has 1200 bytes.

The vectors were made with Python cryptography 46.0.7 from the published
RFC 9001 plaintext. TShark 4.6.9 independently decrypts both packets,
reads the token, and extracts `example.com`. The v2 packet uses the salt,
labels and packet type from RFC 9369. They are regression inputs, not
captures from an external server.

Sources: https://www.rfc-editor.org/rfc/rfc9001.html#appendix-A.2
and https://www.rfc-editor.org/rfc/rfc9369.html#appendix-A.2.

`ack-v1.bin` and `ack-v2.bin` use the same published ClientHello, no token,
and packet number 1. An ACK of server packet 0 precedes CRYPTO. TShark
independently reads both frames and extracts the complete ClientHello.

`partial-v1.bin` and `partial-v2.bin` contain only the first 100 bytes of
that 241-byte ClientHello in one CRYPTO frame. SNI is within that prefix.
The enclosing Initial protection is valid. TShark decrypts CRYPTO100 but
does not return a complete TLS handshake.

`rfc-client-hello.bin` is the complete 241-byte handshake from RFC 9001 A.2.
`coalesced-v1.bin` and `coalesced-v2.bin` carry it across two 600-byte
Initial packets, PN0/1, CRYPTO offsets0/50 and lengths50/191. TShark reads
both packets in one 1200-byte UDP payload and extracts `example.com`.
`short-tail-v1.bin` and `short-tail-v2.bin` use offsets0/200, lengths200/41,
and packet sizes1105/95. Thus the final packet is shorter than 128 bytes.
These variants use the same independent Python cryptography builder.
