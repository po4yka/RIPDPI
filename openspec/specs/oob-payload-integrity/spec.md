# oob-payload-integrity Specification

## Purpose
Preserve the original TCP application stream when the user selects the optional urgent byte method, including failures and invalid configuration.

## Requirements

### Requirement: REQ-OOB-ONCE — Bound urgent emission

The planner MUST emit at most one OOB or Disoob urgent byte, only in the first nonempty original payload at round 1 and stream start 0. Later urgent steps MUST use ordinary split semantics.

#### Scenario: Bound urgent emission

- **WHEN** a chain contains two urgent steps or a later outbound payload requests OOB
- **THEN** only the first eligible urgent step is emitted; normal peer bytes remain the original payload

### Requirement: REQ-OOB-PROGRESS — Preserve send progress

The runtime MUST send the original prefix with progress tracking before sending exactly one urgent byte. An error MUST preserve accepted original bytes and MUST NOT retry an urgent syscall after payload progress or an ambiguous urgent result.

#### Scenario: Preserve send progress

- **WHEN** send buffer pressure causes a prefix or urgent send error
- **THEN** the reported payload progress is accurate and the request is not replayed on the same stream

### Requirement: REQ-OOB-HEX — Parse malformed hex safely

The parser MUST NOT panic or slice UTF-8 at an invalid boundary on a malformed hex escape. It MUST keep the ByeDPI `parse_cform` fallback, which writes the escape characters as literal bytes.

#### Scenario: Parse malformed hex safely

- **WHEN** oob-data or fake data contains a hex marker followed by a Unicode character
- **THEN** configuration parsing succeeds without a panic and keeps the literal fallback bytes

### Requirement: REQ-OOB-OFFSET — Reject unsupported repeats

Configuration admission MUST reject Oob and Disoob repeats greater than one or a nonzero skip. The shared offset parser MUST reject trailing components and MUST retain valid TLS repeat semantics.

#### Scenario: Reject unsupported repeats

- **WHEN** an OOB offset is 2:3:1 or contains a fourth component
- **THEN** configuration parsing fails rather than applying a different strategy

### Requirement: REQ-OOB-BYTE — Validate the native byte contract

UI, import and native codec boundaries MUST accept only an empty default sentinel or one character with code 0 through 255 for OOB data. Native integer values outside that range MUST fail before Char conversion.

#### Scenario: Validate the native byte contract

- **WHEN** a user pastes or imports a character with code 1046 or a native value 65536
- **THEN** the value is rejected before persistence or byte truncation; empty input still resolves to byte a

### Requirement: REQ-OOB-COUNT — Count only application bytes

Application byte counts and payload receipts MUST exclude the inserted urgent byte and MUST accumulate ordinary and urgent prefix writes.

#### Scenario: Count only application bytes

- **WHEN** six original bytes are sent with one OOB insertion
- **THEN** application counts equal six; partial counts include only accepted original bytes

### Requirement: REQ-OOB-PROJECTION — Preserve urgent projection

The generic strategy adapter MUST preserve urgent actions using the existing WriteUrgent variant, including the exact prefix and urgent byte. It MUST NOT replace an urgent action with an ordinary byte insertion.

#### Scenario: Generic adapter receives an urgent action

- **WHEN** a caller requests an OOB plan through the generic strategy trait
- **THEN** the output plan contains WriteUrgent with the exact original prefix and configured byte; no ordinary Write contains the inserted urgent byte

### Requirement: REQ-OOB-TTL-RESTORE — Stop after a failed TTL restore

A failed restoration of a lowered socket TTL MUST fail the current send and
retain accepted payload progress. It MUST NOT be reported as a successful
TTL-unavailable fallback. The implementation MUST NOT send the tail after this
failure. Initial TTL unavailability MAY retain the existing Oob fallback.

#### Scenario: Restoration fails after progress

- **WHEN** a lowered socket rejects restoration after original bytes were accepted
- **THEN** the send reports the restoration error and the accepted progress
- **AND** the caller cannot replay the original payload as uncommitted data

### Requirement: REQ-OOB-TTL-FAMILY — Set the active address family

A socket TTL operation MUST select the option that controls the connected
peer on the current OS. IPv4 peers use IPv4 TTL and native IPv6 peers use IPv6
Hop Limit. Linux and Android IPv4-mapped IPv6 peers MUST use IPv4 TTL for both
read and write. Other hosts MUST retain their native socket-family option.
Success in another address family MUST NOT hide a failure.

#### Scenario: IPv6 socket has independent IPv4 and IPv6 values

- **WHEN** an IPv6 peer socket receives a requested Hop Limit change
- **THEN** the IPv6 value changes and its inactive IPv4 TTL is not changed

#### Scenario: Linux socket connects to an IPv4-mapped IPv6 peer

- **WHEN** the socket has different IPv4 TTL and IPv6 Hop Limit values
- **THEN** the getter reads IPv4 TTL and the setter changes only IPv4 TTL

### Requirement: REQ-OOB-STAGE-DEADLINE — Fail an undrained stage

A send-stage wait MUST return a timeout when its deadline expires while bytes
remain unsent, with or without an initial wait interval.

#### Scenario: Peer does not drain the send queue

- **WHEN** the send queue remains nonempty until the stage deadline
- **THEN** both wait modes return TimedOut instead of reporting completion

### Requirement: REQ-OOB-WIRE-EVIDENCE — Verify the actual Disoob stream

The Disoob packet-smoke check MUST require a strict local echo, original payload
bytes plus one urgent byte at the configured sequence position, TTL 1 for the
lowered prefix and urgent byte, and the restored TTL for the tail. It MUST allow
separate prefix and urgent TCP segments and MUST reject gaps or conflicting bytes.
It MUST NOT treat fake TTL, a family log or unrelated packets as this evidence.

#### Scenario: Prefix and urgent byte use separate segments

- **WHEN** the emitter sends the prefix and one urgent byte in separate segments
- **THEN** the oracle verifies their sequence positions and their TTL values
- **AND** the local application receives exactly the original payload

### Requirement: REQ-OOB-PLATFORM-SCOPE — State the supported stage platforms

Disoob documentation MUST identify Linux and Android as the native stage-wait
platforms. It MUST NOT claim full execution support from a host test adapter.

#### Scenario: A host lacks native send-stage support

- **WHEN** a user reads the Disoob platform table
- **THEN** it states the Linux and Android boundary and the host-test limitation
