# subscriptions/atomic-awg-refresh Specification

## Purpose
Keep subscription-owned AWG credentials and the group generation bound to one current owner.

## Requirements

### Requirement: REQ-AWG-OWNER — Reject stale rotations before mutation

The refresh commit MUST compare the persisted group with its captured snapshot under the same mutation owner that excludes all group and AWG writes. A mismatch MUST return `Superseded` before preparing AWG writes or changing rows, credentials, group metadata, or update timestamps.

#### Scenario: Source or group changes during fetch

- **GIVEN** a valid delayed response would rotate an existing AWG endpoint and preshared key
- **WHEN** its source or group changes, disappears, or a newer refresh commits before acceptance
- **THEN** the response is superseded and the current group, row and credentials remain unchanged

#### Scenario: Competing AWG-only updates share a timestamp

- **GIVEN** two refreshes captured the same subscription revision and clock value
- **WHEN** one valid AWG-only response commits first
- **THEN** its persisted accepted-refresh revision advances and the older response is rejected even though timestamps and relay members are equal

#### Scenario: Accepted-refresh revision cannot advance

- **GIVEN** a stored revision is negative or already the maximum signed 64-bit value
- **WHEN** a valid response attempts to commit
- **THEN** commit fails before preparing AWG writes or a journal entry and no timestamp is synthesized

### Requirement: REQ-AWG-COMMIT — Commit accepted rotations with recoverable group publication

Accepted refreshes SHALL journal AWG and group after-images under one recovery owner and report success only after writes and publication. Stable AWG identity and editor-owned settings MUST survive rotation. Readable prior catalogues MUST recover interrupted commits before later mutations. Unreadable catalogues MUST block AWG replay and preserve bytes and journal. Independent store visibility is not atomic.

#### Scenario: Current response rotates a saved peer

- **GIVEN** a current subscription and a saved peer with a local private key
- **WHEN** a valid response rotates server parameters
- **THEN** the stable peer row is updated, its local key is preserved, and the accepted group timestamp is published

#### Scenario: Cancellation or persistence failure interrupts commit

- **GIVEN** an accepted update has a durable encrypted journal record
- **WHEN** cancellation or a store failure interrupts its writes
- **THEN** success is not reported and recovery installs the complete accepted after-image before another mutation can edit the catalogue

#### Scenario: Cold recovery finds an unreadable catalogue

- **GIVEN** a valid pending subscription journal and an unreadable current catalogue
- **WHEN** recovery starts with an uninitialized group observer
- **THEN** it fails before AWG or group writes, preserves the journal and catalogue bytes, and emits no empty fallback; repair is required before replay
