## Purpose

Provide the actual effective send MSS to socket Lua strategies and preserve compatible behavior when a backend cannot measure it.

## ADDED Requirements

### Requirement: REQ-MSS-001 — Current physical send MSS

The TCP socket backend MUST provide the current positive, u16-representable TCP_INFO snd_mss on each Lua invocation.

#### Scenario: Physical upstream socket

- **WHEN** a Lua flow uses a physical upstream socket with a negotiated MSS different from 1460
- **THEN** Lua receives that socket's send MSS while destination matching keeps the logical target.

### Requirement: REQ-MSS-002 — Compatible unavailable measurement

The Lua engine MUST use 1460 when no valid measured MSS exists. UDP and unmeasured TUN metadata MUST retain no measured TCP MSS. The read MUST require no root privilege or new unsafe operation.

#### Scenario: Missing or zero MSS

- **WHEN** the backend supplies no measurement or zero
- **THEN** Lua retains its prior 1460 fallback without a strategy failure.

### Requirement: REQ-MSS-003 — MSS-bounded bundled segmentation

Bundled tcpseg MUST preserve the complete ordered payload and limit each emitted payload segment to the supplied MSS.

#### Scenario: Multiple MSS values

- **WHEN** tcpseg handles a payload larger than 536, 1200 or 1448 bytes with that measured MSS
- **THEN** its ordered writes reconstruct the payload and each write respects that MSS.
