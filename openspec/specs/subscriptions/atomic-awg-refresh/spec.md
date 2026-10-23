# subscriptions/atomic-awg-refresh Specification

## Purpose
Keep subscription-owned AWG credentials and the group generation bound to one current owner.

## Requirements

### Requirement: REQ-AWG-COMMIT — Commit accepted rotations with recoverable group publication

Accepted refreshes SHALL validate immutable AWG and group after-images under one recovery owner before durable journal prepare. Replay MUST preserve these after-images and publish the complete group catalog to existing collectors. Partial application MUST retain recoverable intent and suppress utility readiness. Unreadable catalogs MUST block replay. Independent stores are not atomically visible.

#### Scenario: Current response rotates a saved peer

- **GIVEN** a current subscription with two saved peers and local private keys
- **WHEN** a valid response rotates server parameters
- **THEN** both stable peer IDs and local keys survive, and the accepted generation and group metadata are published

#### Scenario: Cancellation or persistence failure interrupts commit

- **GIVEN** a refresh can fail before or after prepare, after either peer credential or row write, after group persistence, or before/after completion
- **WHEN** an interruption occurs and recovery is repeated before a competing edit
- **THEN** no durable prepare means no accepted write; otherwise the exact accepted after-images finish, the journal is cleared only on completion, an already completed journal never replays, and the later edit remains authoritative after further recovery
- **AND** replay never repeats ownership CAS, write preparation or generation allocation

#### Scenario: Existing collector observes recovery without a reload

- **GIVEN** a collector subscribed before a partial accepted commit
- **WHEN** recovery installs its full after-image
- **THEN** the collector receives the accepted group without list(), and utility readiness is restored only for the complete catalog
- **AND** complete after-image publication may precede journal completion

#### Scenario: Cold recovery finds an unreadable catalogue

- **GIVEN** a valid pending subscription journal and an unreadable current catalog
- **WHEN** recovery starts with an uninitialized group observer
- **THEN** it fails before AWG or group writes, preserves the journal and catalog bytes, and emits no empty fallback

### Requirement: REQ-AWG-GENERATION — Reject stale group generations before mutation

The refresh commit MUST compare the persisted group and its captured ownershipGeneration under the shared mutation owner. A mismatch MUST return `Superseded` before any write preparation. Every add/replacement, effective edit, restore and accepted refresh MUST receive a fresh persisted generation; caller-supplied generations MUST NOT restore ownership. No-op conditional edits MUST preserve their generation.

#### Scenario: Source or group changes during fetch

- **GIVEN** a valid delayed response would rotate an existing AWG endpoint and preshared key
- **WHEN** its source or group changes, disappears, or a newer refresh commits before acceptance
- **THEN** the response is superseded and the current group, row and credentials remain unchanged

#### Scenario: Identical content returns after an intervening mutation

- **GIVEN** a response captured the stored group before delete/recreate, edit/revert, backup restore or compensation
- **WHEN** the same group ID and contents reappear
- **THEN** a different persisted generation rejects the response before preparing any write

#### Scenario: Competing AWG-only updates share a timestamp

- **GIVEN** two refreshes captured the same persisted generation and clock value
- **WHEN** one valid AWG-only response commits first
- **THEN** its generation changes and the older response is rejected even when all other metadata is equal

### Requirement: REQ-AWG-GENERATION-MIGRATION — Persist ownership before exposure or replay

Existing groups and pending subscription after-images MUST receive a one-way durable ownership upgrade before exposure or replay. Failed upgrades MUST preserve recoverable input and prevent publication or AWG writes. Reads and replay MUST preserve assigned generations; serializer defaults MUST NOT allocate them. Local generations MUST NOT enter exported network fingerprints.

#### Scenario: Existing groups are opened repeatedly

- **GIVEN** a stored previous-version group collection
- **WHEN** it is read, read again and opened by a fresh catalog instance
- **THEN** all reads use the same durably migrated generations and snapshot alone does not emit a group flow

#### Scenario: Legacy accepted journal is interrupted during upgrade

- **GIVEN** a pending subscription intent without generations
- **WHEN** journal upgrade fails or replay later interrupts
- **THEN** no AWG writes precede successful upgrade, and subsequent recovery preserves the upgraded generations and original row/credential after-images

#### Scenario: Failed commit changes only the process cache

- **GIVEN** a group migration or journal mutation changed the shared process cache but its synchronous disk commit failed
- **WHEN** the same or a fresh store instance reads or mutates that state
- **THEN** access remains blocked until a checked synchronous write confirms the exact cached state, with no new generation allocation, group publication or AWG replay before confirmation
- **AND** a restart before confirmation uses only the old durable state, while a restart after confirmation uses exactly the confirmed generation or journal bytes
