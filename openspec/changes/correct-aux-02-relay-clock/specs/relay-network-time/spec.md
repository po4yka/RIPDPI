## ADDED Requirements

### Requirement: REQ-AUX-02-TRUST — Relay time has a bounded trust scope

A relay factory MUST own the clock used by its connections. Only a validated response bound to that relay's request MAY calibrate it. Changes to peer time MUST NOT affect other relay factories. An older observation MUST NOT replace a newer observation. Endpoint aliases MUST NOT count as independent votes.

#### Scenario: Replies from independent profiles

- **WHEN** a valid reply calibrates the clock of one profile
- **THEN** the estimate and acceptance windows of the other profiles remain unchanged

#### Scenario: Refused or unrelated reply

- **WHEN** the first reply is invalid for the request or an older observation arrives late
- **THEN** the existing calibration is not replaced

### Requirement: REQ-AUX-02-RECOVERY — A new runtime can use corrected device time

A new relay runtime MUST start from the device wall clock until it has valid calibration evidence. Recovery MUST retain the wire freshness checks and MUST NOT send the application's UDP payload a second time.

#### Scenario: Correct device time and restart

- **WHEN** the device time is corrected and the relay is stopped and started
- **THEN** the new relay uses the corrected time without inheriting an old anchor

### Requirement: REQ-AUX-02-REPLAY — A live UDP association keeps one acceptance timeline

A SIP022 UDP association MUST use one monotonic time source for freshness and retention of receive replay state. Replacing a peer's replay window MUST NOT let an already accepted packet become valid again.

#### Scenario: Clock correction and session rotation

- **WHEN** the profile clock steps back while a UDP association is active
- **THEN** the association continues advancing on its original timeline and keeps the required replay protection

### Requirement: REQ-AUX-02-CLOCK — The clock handles discontinuities and bounds

A clock MUST keep a consistent monotonic epoch if an operating-system read fails. The protocol consumers MUST reject invalid clock inputs safely and MUST format timestamp errors without overflow.

#### Scenario: Low-level clock read fails

- **WHEN** a read fails after selection of a monotonic time source
- **THEN** the reported elapsed time never changes epoch or goes backwards
