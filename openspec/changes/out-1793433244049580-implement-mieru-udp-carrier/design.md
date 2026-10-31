## Context

The TCP carrier already implements the cipher, metadata headers, segments,
sessions and multiplexing (`ripdpi-mieru`), verified against pinned upstream
commit `155ebbd60f86e472586a60d7ffe58ec8f8682cb1` by the CI oracle in
`scripts/tests/outbound-mieru-oracle/`, which today binds only a TCP
`PortBinding`. The UDP carrier shares the metadata layouts but adds its own
packet underlay and a KCP-like session ARQ that the published document does
not fully specify.

## Goals / Non-Goals

- Goal: a UDP carrier that interoperates with the pinned upstream server and
  keeps the TCP carrier's nonce and fail-closed guarantees.
- Non-goal: UDP ASSOCIATE over the TCP carrier. That is a separate relay
  capability (`RelaySession::open_datagram`) and does not satisfy this change.
- Non-goal: guessing unspecified wire behaviour.

## Decisions

- Semantics first: transcribe the upstream UDP underlay and ARQ into
  `PROTOCOL.md` from the pinned source, with citations, before writing code.
- Reuse the existing cipher, metadata and segment code; add a datagram segment
  codec sized to the MTU with packet padding.
- One protected `UdpSocket` per carrier through `protect_carrier_socket` and the
  runtime's explicit `SocketProtectionPolicy`.
- Split `MieruMuxConnection` so the multiplexer runs over a writer queue and a
  reader task instead of a byte stream.
- Interop is the release gate: extend the CI oracle with
  `TransportProtocol_UDP` and `udp_*` cases; registry rows flip to UDP only
  after they pass.

## Risks / Trade-offs

- ARQ timing interacts with mobile networks; loss and reordering tests must
  cover retransmission backoff and idle teardown.
- The change is large (estimated 2,000 to 2,500 lines). It must not weaken
  the TCP carrier, which keeps its existing tests.

## Migration Plan

No persisted or wire schema migration. Profiles with `protocol=udp` stop being
rejected once the carrier and its interop gate land.
