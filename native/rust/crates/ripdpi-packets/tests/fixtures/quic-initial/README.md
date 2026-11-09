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
