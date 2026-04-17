# First-Flight IR

## Scope

Phase 4 introduces a semantic "first-flight" intermediate representation shared
by TLS ClientHello shaping and QUIC Initial shaping. The IR is intentionally
above tactic-family and emitter details:

- it describes the first application-visible handshake flight
- it preserves parser-derived semantic landmarks
- it records natural wire boundaries without deciding how they will be emitted
- it leaves root/non-root and fake/raw lowering decisions for later phases

This document covers the 4.1 boundary and invariants for the initial
implementation.

## Module Boundary

The initial IR lives in `ripdpi-desync/src/first_flight_ir.rs`.

That module owns:

- semantic IR types for TLS and QUIC first-flight state
- normalization from parser/layout outputs into IR
- invariants around authority spans, ALPN decoding, ECH presence, GREASE
  detection, record boundaries, QUIC CRYPTO frame layout, and desired boundary
  placeholders

It does not own:

- tactic planning in `plan_tcp.rs` / `plan_udp.rs`
- fake-packet construction in `fake.rs`
- emitter restrictions or platform capability lowering in `ripdpi-runtime`
- raw parser internals in `ripdpi-packets`

`ripdpi-packets` exposes additive inspection helpers only:

- `parse_tls_client_hello_layout(...)`
- `parse_tls_client_hello_handshake_layout(...)`
- `parse_quic_initial_layout(...)`

Those functions provide layout facts; the IR module converts those facts into a
planner-facing semantic model.

## Invariants

### TLS ClientHello

- `authority_span` must point at the exact SNI hostname bytes.
- `authority` must be a direct copy of `raw[authority_span]`.
- `record_boundaries` must preserve the original first TLS record header and
  payload ranges when the source is a TLS record.
- `extensions` must stay in original wire order.
- `has_ech` is true when the ECH extension is present in the parsed extension
  table; no rewrite semantics are attached yet.
- `grease` only records detected GREASE values; it does not classify intent.
- `desired.tcp_segment_boundaries` records semantic boundaries worth preserving
  or targeting later, but does not force emitter behavior in this phase.

### QUIC Initial

- QUIC normalization is layered on top of `parse_quic_initial_layout(...)`.
- `tls_client_hello` inside QUIC is normalized from the defragmented CRYPTO
  stream, not from the encrypted datagram bytes.
- `crypto_frames` preserve CRYPTO stream offsets and decrypted payload byte
  ranges for each frame fragment.
- `desired.udp_datagram_boundaries` preserves the original datagram boundary.
- `desired.crypto_frame_boundaries` preserves CRYPTO-frame stream ends, but does
  not yet imply any re-packetization strategy.

## Deliberate Non-Goals For This Slice

- No tactic migration from `plan_tcp.rs` or `plan_udp.rs`
- No shared emission pipeline
- No lowering policy changes
- No root/non-root branching changes
- No terminal-step restriction cleanup

Those stay in later Phase 4 slices after the IR shape is stable.
