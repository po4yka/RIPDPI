## Purpose

Provide bounded gRPC gun codec streams with correct completion, cancellation, and service routing.

## ADDED Requirements

### Requirement: REQ-GRPC-FLUSH — Drain framed writes

Flush and shutdown MUST drain accepted payload to the wire endpoint and return wire write errors.

#### Scenario: Drain framed writes

- **WHEN** a writer has buffered payload
- **THEN** flush completes only after that payload reaches the wire buffer

### Requirement: REQ-GRPC-PATH — Escape logical service names

The path helper MUST escape service segments like pinned Xray while retaining documented slash aliases.

#### Scenario: Escape logical service names

- **WHEN** a service name is a/b
- **THEN** the path is /a%2Fb/Tun

### Requirement: REQ-GRPC-CANCEL — Own codec work

The transport MUST own codec state and cancel pending work when it is dropped.

#### Scenario: Own codec work

- **WHEN** the caller drops an idle transport but keeps wire halves
- **THEN** no codec task remains and the wire endpoints observe closure

### Requirement: REQ-GRPC-RESULT — Preserve carrier completion

The transport MUST require an explicit carrier result for clean EOF and preserve errors after received payload.

#### Scenario: Preserve carrier completion

- **WHEN** the carrier completes with an error after a valid Hunk
- **THEN** the reader receives the payload followed by the carrier error

