# Change: Implement the Mieru UDP carrier

Task ID: `OUT-1793433244049580`

## Why

`ripdpi-mieru` implements only the TCP carrier. A profile with `protocol=udp`
is rejected with `MieruError::UdpUnsupported` before DNS or dial
(`client.rs`, `mux.rs`, and the relay facade
`ripdpi-relay-tls-transports/src/mieru.rs`). Parent task OUT-1786264762917513
kept this criterion open under REQ-OUT-1786264762917513-007; the user approved
on 2026-10-31 moving it into this follow-up task.

A conforming client cannot be written from the published protocol document
alone. Upstream `docs/protocol.md` at the pinned commit
`155ebbd60f86e472586a60d7ffe58ec8f8682cb1` gives the per-segment nonce, the
one-segment-per-packet MTU bound, the shared metadata layouts, and states that
`seq`, `unAckSeq` and `windowSize` are used for flow control. It does not
specify the UDP payload nonce order, acknowledgement semantics, retransmission
timing, window units, fragment ordering and reassembly, retransmission of
open/close segments, or server session keying and idle timeouts. Guessing
them would produce a client that only agrees with itself.

## What Changes

- Transcribe the UDP packet underlay and session ARQ semantics from the pinned
  upstream source into `native/rust/crates/ripdpi-mieru/PROTOCOL.md`, as was
  done for the TCP carrier.
- Implement the UDP carrier: datagram segment codec, a protected `UdpSocket`
  underlay with demultiplexing by session, and per-session ARQ (send buffer,
  retransmission with backoff, acknowledgements, reorder buffer, window,
  fragment reassembly, open/close retransmission, idle timeout).
- Let the multiplexer run over a datagram carrier instead of assuming an
  `AsyncRead + AsyncWrite` stream.
- Extend the pinned upstream Mieru CI oracle with a UDP port binding and
  `udp_*` interoperability cases, then mark Mieru UDP-capable in the relay
  capability registries.

## Capabilities

### New Capabilities

- `mieru-udp-carrier`: the Mieru outbound runs over its UDP transport and
  interoperates with the pinned upstream server.

### Modified Capabilities

- None.

## Impact

- `ripdpi-mieru` and the Mieru facade in `ripdpi-relay-tls-transports`.
- The Mieru rows of the serialized relay capability registries
  (`RelayUdpCapabilities.kt`, `transport_descriptor.rs`, `RelayKindDescriptor.kt`).
- `scripts/tests/outbound-mieru-oracle/` and `scripts/tests/run-outbound-interop.py`.
- No new dependency is expected; the estimate is about 2,000 to 2,500 lines of
  Rust including loss and reordering tests.
