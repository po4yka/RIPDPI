## Context

The pure codec rejects non-binary messages. The runtime receiver currently passes Ping/Pong to it and then treats normal control traffic as InvalidData.

## Goals / Non-Goals

- Goal: handle controls at the transport layer.
- Non-goal: change the pure codec API or Close deadlines.

## Decisions

- Skip Ping/Pong and continue transport reads. Tungstenite queues Pong and attempts its automatic nonblocking flush on the next read.
- Do not take the sender mutex or await a flush for Ping. Close keeps its original state and bounded deadline.
- Verify independently encoded frames and unmask the client reply in the test.

## Contracts and ownership

- Native lifecycle agent owns wireguard-ws receive code and loopback tests. No shared serialized files change.

## Risks / Trade-offs

- Backpressure can delay Pong output, but must not block inbound data or observation of Close. Existing transport buffering owns the reply.

## Migration Plan

No migration. Revert for rollback. Run carrier tests and async review before integration.
