# xudp-source-authority Specification

## Purpose
Deliver XUDP replies to the correct client destination when the server omits
repeated source metadata, without accepting authority from another association.

## Requirements

### Requirement: REQ-XUDP-INITIAL-SOURCE — Establish initial request authority

The XUDP receiver MUST accept an omitted reply source after the same carrier
has accepted its initial NEW request. It MUST use that request target as the
initial authority, including when the response arrives immediately.

#### Scenario: Immediate reply without a source

- **WHEN** an accepted initial NEW request targets A and the server immediately replies without a source
- **THEN** the client receives the exact payload from A
- **AND** later uplink targets cannot change that initial authority

#### Scenario: Two targets before the first reply

- **WHEN** the initial NEW request targets A and a later uplink targets B before a source-free reply arrives
- **THEN** the initial omitted-source authority remains A

### Requirement: REQ-XUDP-SOURCE-ISOLATION — Preserve response authority boundaries

Explicit response sources MUST apply only to their own datagram. A later
omitted source MUST use the initial NEW target, not the last explicit response. Missing authority, invalid session metadata and malformed frames MUST
remain errors. Source authority MUST NOT cross carrier or runtime generations.

#### Scenario: No established request

- **WHEN** a reply omits its source before an initial request is accepted
- **THEN** the receiver rejects it without delivering the payload to a guessed destination

#### Scenario: Explicit response source and a new carrier

- **WHEN** a response supplies a valid explicit source
- **THEN** only that datagram uses the explicit source
- **AND** the next source-free reply uses the initial NEW target
- **AND** a new carrier cannot inherit either source

### Requirement: REQ-XUDP-SOURCE-LIFECYCLE — Publish only accepted request authority

Cancellation before request admission MUST NOT establish source authority.
The implementation MUST preserve bounded queues, cancellation and socket cleanup.
It MUST NOT expose request addresses or payloads in new diagnostics.

#### Scenario: Cancellation before queue admission

- **WHEN** request admission is cancelled before the first request can enter its carrier queue
- **THEN** no initial source authority is published
- **AND** a later valid first request can establish its own authority

#### Scenario: Physical TCP reserve DNS

- **WHEN** the authorized USB scenario selects its TCP reserve after Hysteria peer loss
- **THEN** the separate-UID DNS request succeeds with a new independent peer receipt
- **AND** the remaining explicit UDP recovery and owned cleanup complete
