## Purpose

Keep existing packet parameter behavior reliable, bounded, and consistent with its declared transport scope.

## ADDED Requirements

### Requirement: REQ-DPI27-FEEDBACK — Associate feedback with an attempt

The implementation MUST apply feedback only to the matching cache generation, pin the TTL that succeeded, and prevent stale failure feedback from undoing a newer success.

#### Scenario: Associate feedback with an attempt

- **WHEN** two sends use TTL 8, one fails and the other succeeds
- **THEN** the successful TTL 8 is pinned; feedback from an evicted generation has no effect.

### Requirement: REQ-DPI27-IDENTIFICATION — Reserve live IPv4 IDs by IP tuple

The implementation MUST reserve each non-atomic raw TCP IPv4 Identification through the existing sender lease owner, including genuine segments, and fail before emission on exhaustion.

#### Scenario: Reserve live IPv4 IDs by IP tuple

- **WHEN** two source ports request the same nonzero preferred ID for the same IP tuple
- **THEN** the resulting packets have distinct live IDs and unchanged genuine payload.

### Requirement: REQ-DPI27-SACK — Prove the supported SACK layout

The implementation MUST drop an incoming segment only when the supported fixed layout and SACK length bounds prove the matched byte is the SACK option header.

#### Scenario: Prove the supported SACK layout

- **WHEN** a Timestamp value contains byte 5 at TCP offset 34 but no SACK option exists
- **THEN** the whole segment is accepted without modification.

### Requirement: REQ-DPI27-UDP — Keep UDP TTL fixed

The implementation MUST preserve the saved fixed UDP TTL and omit TCP adaptive configuration from synthesized UDP groups.

#### Scenario: Keep UDP TTL fixed

- **WHEN** fixed TTL is 11 and adaptive TCP fallback is 8
- **THEN** UDP retains TTL 11 with no auto_ttl.

### Requirement: REQ-DPI27-CANDIDATES — Enumerate bounded candidates once

The implementation MUST enumerate every TTL in the configured window exactly once per cycle for each bias sign, including TTL 255.

#### Scenario: Enumerate bounded candidates once

- **WHEN** a window is 254 through 255 with seed at an edge
- **THEN** the sequence has no duplicate before the next cycle.

### Requirement: REQ-DPI27-TIMESTAMPS — Describe receive timestamp filtering

The implementation MUST describe strip_timestamps as a receive-side whole-segment filter, preserve its direction and filter-priority gates, and avoid claims of outbound option removal.

#### Scenario: Describe receive timestamp filtering

- **WHEN** the timestamp filter is attached to one socket and the same input is sent in both directions
- **THEN** matching inbound bytes are dropped and outbound bytes remain unchanged.

