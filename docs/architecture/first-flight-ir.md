# First-Flight IR

The first-flight intermediate representation is the semantic model shared by
TLS ClientHello shaping and QUIC Initial shaping. It lives in
`native/rust/crates/ripdpi-desync/src/first_flight_ir.rs`.

## Boundary

The IR owns:

- TLS and QUIC first-flight semantic types;
- normalization from parser/layout outputs into planner-facing state;
- authority spans, ALPN decoding, ECH presence, GREASE detection, TLS record
  boundaries, QUIC CRYPTO frame layout, and desired split boundaries.

It does not own:

- tactic planning in `plan_tcp.rs` or `plan_udp.rs`;
- fake-packet construction;
- platform capability lowering in `ripdpi-runtime`;
- parser internals in `ripdpi-packets`.

`ripdpi-packets` provides additive layout helpers such as
`parse_tls_client_hello_layout(...)`,
`parse_tls_client_hello_handshake_layout(...)`, and
`parse_quic_initial_layout(...)`. The IR converts those wire facts into a
stable planner model.

## TLS Invariants

- `authority_span` points at the exact SNI hostname bytes.
- `authority` is copied directly from `raw[authority_span]`.
- `record_boundaries` preserves the original first TLS record header and
  payload ranges when the source is a TLS record.
- Extensions stay in original wire order.
- `has_ech` only records ECH extension presence; it does not imply rewrite
  semantics.
- GREASE fields only record detected GREASE values.
- Desired TCP segment boundaries are semantic hints for later lowering, not
  emitter commands.

## QUIC Invariants

- QUIC normalization uses `parse_quic_initial_layout(...)`.
- The nested TLS ClientHello is normalized from the defragmented CRYPTO stream,
  not from encrypted datagram bytes.
- CRYPTO frame metadata preserves stream offsets and decrypted payload byte
  ranges for each fragment.
- Desired UDP datagram and CRYPTO-frame boundaries preserve natural wire
  boundaries without forcing a specific packetization strategy.
