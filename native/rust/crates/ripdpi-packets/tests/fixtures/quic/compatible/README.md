# QUIC response accounting vectors

These public packets check response accounting. They do not establish a real TLS handshake or endpoint compatibility. Initial integrity does not authenticate a server.

`generate.rs` uses the pinned rustls 0.23.46 QUIC packet/header protection API with its ring provider. For Retry it uses ring 0.17.14 AEAD sealing with the public RFC 9001/RFC 9369 Retry constants. It does not call RIPDPI packet crypto or parsing. The plaintext inputs come from the adjacent RFC 9001 Appendix A and RFC 9369 Appendix A public vectors.

The full clients offer v1 and v2 in `version_information` (0x11). Partial clients carry half of ClientHello in a valid Initial. The no-SNI client has a complete ClientHello without server_name. Unrelated responses have valid packet protection with a different destination CID. The post-Retry pair uses its request DCID, `706f737472657472`, and a public test token.

The grease clients also contain one empty `grease_quic_bit` (0x2ab2). Grease Initial and Retry responses have Fixed Bit 0. The producer protects the raw header with that bit already clear. Cross-version Retry vectors are integrity-valid negative cases: Retry must retain the request's original version.

Specifications: [RFC 9368 sections 2.3 and 3](https://www.rfc-editor.org/rfc/rfc9368.html#section-3), [RFC 9369 section 4.1](https://www.rfc-editor.org/rfc/rfc9369.html#section-4.1), [RFC 9287 section 3.1](https://www.rfc-editor.org/rfc/rfc9287.html#section-3.1), and [RFC 9001 Appendix A](https://www.rfc-editor.org/rfc/rfc9001.html#appendix-A).

Reproduce from the repository root. The temporary example uses existing exact pinned dev dependencies; no manifest change is needed:

```sh
mkdir -p native/rust/crates/ripdpi-proxy-runtime/examples
cp native/rust/crates/ripdpi-packets/tests/fixtures/quic/compatible/generate.rs native/rust/crates/ripdpi-proxy-runtime/examples/dpi24_compatible_oracle.rs
cd native/rust
build-gate --lane ripdpi -- cargo run -p ripdpi-proxy-runtime --example dpi24_compatible_oracle --locked --jobs 4 -- "$PWD/crates/ripdpi-packets/tests/fixtures/quic"
rm crates/ripdpi-proxy-runtime/examples/dpi24_compatible_oracle.rs
```

This command completed with Rust 1.100.0. TShark 4.6.9 independently decoded raw client/server pairs wrapped in IPv4 UDP PCAPs (`tshark -r <pair>.pcap -V`):

- v1→v2, v2→v1, and post-Retry pairs show client version_information and server CRYPTO / TLS ServerHello.
- Same-version and cross-version grease Initial pairs show the empty client grease offer, server Fixed Bit False, and CRYPTO / TLS ServerHello.
- Both same-version grease Retry pairs show `Retry Integrity Tag: ... [verified]`.

All Initial datagrams are 1200 bytes. Retry packets are 43 bytes (44 for unrelated-CID negative cases). SHA-256:

```text
266ebb480b7155c3373e352906c817dc2cfb7a0506b082c12a170f1f6f3d7e2b  generate.rs
9080aef23a07a9c04e29a0cd2eea3bd708fb5b497d7600d66120f3e974508a0a  v1-grease-client.bin
6eadbccb20168303a64cd858ab46a78dd465bd5fdf0215dfd2ff09dc9e897728  v1-grease-initial.bin
71867029a073df432400b796053b6f190b8a67d0df174794e277774826482fe9  v1-grease-partial-client.bin
46a9a4ee47daa10d8ca3c6fbe1ceb2f68aceca5a7bb093b4a6900d9996e4afd1  v1-grease-retry.bin
488208c3af2555cb0d1effc46e18f88ffefd119455e0372c02767cbd01781f67  v1-grease-unrelated-initial.bin
1b29bd3bcc20440586a84e30952a03046d40f0d4e4895162bba3b9d17d9a077e  v1-grease-unrelated-retry.bin
5dfa56eb41a65b4a6f71dfc12b125800c115ca47805a1e9591f44f48c6cd6ba2  v1-v2-client.bin
e1162c1b3ee66108557dd9eb71c20d7271e16e708ecb3a1f91f32e28f589d2ef  v1-v2-grease-initial.bin
be011d710f8c45126d2ed80ed396aec959e8a5409ef51e2f2f079352f49224df  v1-v2-grease-retry.bin
fa0832c5a7c3a7b01d839038164df60d8f75e7f2434082a592d386d35690a326  v1-v2-no-sni-client.bin
8661c1b54f5a0d84e0ae86286841d8cbb1e8411efcab75b7793d3ce19b088127  v1-v2-partial-client.bin
6437c21046c832d3f7e89e532f55f0457e233938cf76ea4dbd7ba22c21d3f703  v1-v2-post-retry-client.bin
6e31cf66e40778b5b9b9eca4876a5f892c387497c9217992206eacad3fda9bbb  v1-v2-post-retry-server.bin
1f706ee8d25bc8ffe9251431f796891ca498799972d4dc2d8ddaaf314c856418  v1-v2-server.bin
d0385112eb0762824933eb57e81ebb070446b07aacaaf892202368a02630f4b4  v1-v2-unrelated-server.bin
fdc2cc230aa51df4a8d9f4fff0263b51370cb7725c3b9cb844ea82421ed0fc44  v2-grease-client.bin
e1162c1b3ee66108557dd9eb71c20d7271e16e708ecb3a1f91f32e28f589d2ef  v2-grease-initial.bin
80feb2e1964e8c408f3c3f3a5084974d0e5e3ebeb6e2f667f79f772ec386be92  v2-grease-partial-client.bin
be011d710f8c45126d2ed80ed396aec959e8a5409ef51e2f2f079352f49224df  v2-grease-retry.bin
2cea31e9e6f0f873c4ecf68b2a9f53115e13ec3637634c526a43e2dda7e3b553  v2-grease-unrelated-initial.bin
fbe9e6ffedbd99a89404fb33b961969ca4a07109f988be61e363d8c715066cce  v2-grease-unrelated-retry.bin
56b68445f42b70c33f705ebe1024d191a66b0a99c7ecb2e0ea7561f80a7a0ac8  v2-v1-client.bin
6eadbccb20168303a64cd858ab46a78dd465bd5fdf0215dfd2ff09dc9e897728  v2-v1-grease-initial.bin
46a9a4ee47daa10d8ca3c6fbe1ceb2f68aceca5a7bb093b4a6900d9996e4afd1  v2-v1-grease-retry.bin
6ac40bf81974a2a1643553c47b9ae4ba2212b0dd83b2fb00ae12629f4ebe98ad  v2-v1-partial-client.bin
8db5be2bf3161992b2a77badafa7733711ecfa7c727508e6a711528c998ef047  v2-v1-server.bin
```
