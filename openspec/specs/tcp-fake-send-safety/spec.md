# tcp-fake-send-safety Specification

## Purpose
Keep real TCP payloads intact when fake packet techniques run, fail, or are skipped,
and keep helper payload admission bounded and compatible.

## Requirements

### Requirement: REQ-FAKE-ACTIVATION — Preserve skipped payloads

The implementation MUST send the original payload when group activation fails.

#### Scenario: A fake group is outside its round range

- **WHEN** a fake group with entropy padding permits round one and receives round two
- **THEN** the peer receives exactly the input bytes and the receipt reports the input length

### Requirement: REQ-FAKE-CLEANUP — Restore queued real bytes before TTL

The implementation MUST restore real bytes in queued pages before it restores normal TTL on every fake-send exit.

#### Scenario: A failure follows a successful splice

- **WHEN** a fake prefix is queued and a later send stage fails
- **THEN** the queued pages contain the real prefix before normal TTL is restored

### Requirement: REQ-FAKE-REPLAY — Stop replay after a possible commit

The implementation MUST prevent downgrade and replay after possible payload emission.
It MUST distinguish a possible commit from an exact byte count.

#### Scenario: A raw operation fails after emission starts

- **WHEN** an optional raw send fails after a packet may have been emitted
- **THEN** the implementation does not send the prefix again through the stream fallback

#### Scenario: An opaque fake boundary returns an error

- **WHEN** a fake operation returns an error without reliable progress information
- **THEN** the runtime reports possible commitment and does not reconnect to replay the request

### Requirement: REQ-FAKE-GENUINE — Preserve genuine packet modifiers

The implementation MUST use the real socket or configured TTL for genuine packets.
Decoy MD5 signatures MUST apply only to fake packets.

#### Scenario: A raw original shares a batch with a fake

- **WHEN** a fake uses TTL eight and MD5 while the real stream uses TTL sixty-four
- **THEN** the first genuine packet uses TTL sixty-four and has no MD5 option

### Requirement: REQ-FAKE-INPUT — Parse inline bytes without panic

The fake-data parser MUST handle malformed Unicode escapes without panic and
MUST preserve the existing behavior of valid and unknown escapes.

#### Scenario: A hex escape crosses a Unicode boundary

- **WHEN** fake-data contains a backslash, x, A, and a multibyte e-acute character
- **THEN** parsing returns the existing fallback bytes without panic

### Requirement: REQ-FAKE-SEQUENCE — Wrap sequence offsets

The implementation MUST add TCP sequence offsets modulo 2^32 and MUST reject
offsets that cannot be represented by the supported offset type.

#### Scenario: A fake offset crosses the sequence boundary

- **WHEN** base sequence is 0xfffffffc and the next fake offset is eight
- **THEN** the serialized sequence is four

### Requirement: REQ-FAKE-HELPER — Admit bounded helper payloads

The helper MUST support bounded fake and ordered TCP requests above the control
frame limit only when it advertises the bulk limits. Nonbulk commands MUST keep
their existing 8192-byte semantic limit. The bulk envelope MUST NOT exceed one
MiB, each payload span MUST NOT exceed 65535 bytes, the aggregate payload MUST
NOT exceed three such spans, and ordered requests MUST NOT exceed 64 segments.

#### Scenario: A capable helper receives a normal fake span

- **WHEN** a valid fake request contains original and fake spans of 2048 or 16384 bytes
- **THEN** the negotiated helper transport accepts the request within the bounded envelope

#### Scenario: A legacy helper has no bulk advertisement

- **WHEN** a request exceeds the control limit and preflight does not advertise bulk limits
- **THEN** the client does not send the oversized command to that helper

#### Scenario: A request exceeds its command limits

- **WHEN** a decoded request exceeds its payload, segment, or command-specific frame bound
- **THEN** it is rejected before privileged dispatch and before packet emission

### Requirement: REQ-FAKE-IPID — Reject unsupported exact zero IDs

The implementation MUST reject requested exact zero IPv4 IDs before packet
emission when the backend cannot preserve them. IPv6 behavior MUST remain
independent from IPv4 Identification settings.

#### Scenario: An IPv4 fake requests zero on IP_HDRINCL

- **WHEN** an IPv4 fake or ordered operation requests exact zero Identification
- **THEN** it returns an unsupported capability error before helper or local emission

#### Scenario: An IPv6 operation carries an IPv4-only mode

- **WHEN** an IPv6 operation carries the zero IPv4 Identification setting
- **THEN** that setting does not introduce an IPv4-only rejection
