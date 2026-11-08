## MODIFIED Requirements

### Requirement: REQ-UDP-CONTROL-LIFETIME — Invalidate a closed upstream control

The implementation MUST ensure that proxy upstream and TUN client UDP bindings are not used after observed control EOF, reset, or unexpected control data. A later datagram MUST use a new association rather than the old relay socket.

#### Scenario: Invalidate a closed upstream control

- **WHEN** the upstream closes control while its UDP socket remains open
- **THEN** a later client datagram is not sent on that old binding.

#### Scenario: Reject a stale TUN downlink

- **WHEN** a TUN association receives a queued relay reply after control closure is observed
- **THEN** it does not deliver that reply to the TUN.
