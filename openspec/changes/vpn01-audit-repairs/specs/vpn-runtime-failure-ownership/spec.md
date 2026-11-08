## Purpose

Preserve VPN resource ownership across service replacement and terminal native
packet-device errors without changing the network or configuration contracts.

## ADDED Requirements

### Requirement: REQ-VPN-PREDECESSOR — Wait for predecessor resource retirement

The VPN service MUST NOT acquire shared lifecycle resources while a predecessor
can still release them. The startup wait MUST stay bounded. Expiry MUST reject
the acquisition without stopping resources for a later owner. Background teardown
tracking MUST include the full predecessor chain, and a later start MUST be able
to retry after that chain completes.

#### Scenario: Start wait expires before old cleanup completes

- **GIVEN** predecessor cleanup still owns shared resources
- **WHEN** the bounded wait expires during a new start
- **THEN** no new session graph or shared resource owner is acquired
- **AND** a retry can acquire resources after predecessor cleanup completes

#### Scenario: An intermediate instance is destroyed

- **GIVEN** an older cleanup remains incomplete
- **WHEN** an intermediate instance is destroyed before that cleanup completes
- **THEN** its published teardown job remains incomplete until the older chain completes
- **AND** a later instance cannot bypass the older cleanup

### Requirement: REQ-TUN-WRITE-RETIRE — Retire sessions after terminal output errors

The native packet loop MUST return terminal packet-write or write-readiness
errors after it cancels and retires owned TCP and UDP sessions. Normal
cancellation MUST retain its existing bounded shutdown behavior. Transient
backpressure MUST NOT be treated as a terminal failure.

#### Scenario: Packet output fails with live sessions

- **GIVEN** the loop owns a live session and the packet fd can no longer send
- **WHEN** a terminal write error occurs
- **THEN** the loop returns the error after its owned session sockets and tasks retire

### Requirement: REQ-TUN-READ-TERMINATE — Stop after terminal input errors

The native packet loop MUST terminate after terminal read or read-readiness
errors and unexpected EOF. It MUST preserve transient WouldBlock handling,
return the failure to its lifecycle owner, and use the same session-retirement
path as other terminal errors. It MUST NOT repeatedly log the same terminal error.

#### Scenario: The packet device closes its input

- **WHEN** a real packet-fd input returns EOF or a terminal read error
- **THEN** the packet loop exits within the test deadline and retires owned sessions
- **AND** it does not continue a busy read loop

### Requirement: REQ-VPN-COMPATIBILITY — Preserve existing authority and privacy

The correction MUST preserve JNI schema, configuration fields, socket
protection, non-root support, and the physical-network authority contract.
Ambiguous legacy observations MUST remain unavailable. Logs MUST NOT add user
payloads, raw identifiers, or secrets. Documentation MUST describe the current
PacketFd owner and distinguish host checks from Android acceptance.

#### Scenario: Legacy observations remain ambiguous

- **WHEN** more than one legacy physical candidate exists without selected authority
- **THEN** the runtime retains unknown physical evidence and safe defaults
- **AND** it does not use the application VPN as a physical-network substitute
