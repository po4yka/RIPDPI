# physical-network-handover-authority Specification

## Purpose
Keep Android handover and tunnel parameters tied to one selected physical
network while VPN overlay state changes independently.

## Requirements

### Requirement: REQ-PHYSICAL-AUTHORITY — Selected physical evidence

The runtime MUST use complete selected physical callback evidence for network
fingerprints and tunnel parameters. The existing passive observer MUST remain
the only physical observer. Application-default VPN state MUST NOT supply a
physical fingerprint or tunnel MTU.

#### Scenario: Stable physical path with changing VPN overlay

- **WHEN** a selected physical path stays unchanged and the VPN overlay changes
- **THEN** its physical fingerprint and tunnel parameters remain unchanged
- **AND** no handover restart is emitted

#### Scenario: Physical MTU avoids recursive tunnel subtraction

- **WHEN** an existing VPN has a reduced MTU and the physical path is ready
- **THEN** tunnel parameters use the physical MTU and current metered state
- **AND** encapsulation overhead is subtracted exactly once

### Requirement: REQ-PHYSICAL-CHANGES — Selected semantic changes

The runtime MUST classify true selected transport changes as transport_switch.
Selected DNS, private-DNS, route, MTU or link changes MUST remain observable as
link_refresh when no stronger handover classification applies. Unrelated
callbacks and repeated identical evidence MUST NOT trigger a handover.
Cooldown duplicate suppression MUST compare the applied physical signature
as well as the stored scope key without changing its ten-second duration.

#### Scenario: Transport and link changes remain observable

- **WHEN** the selected ready path changes transport, DNS, private DNS or MTU
- **THEN** the monitor emits the matching transport_switch or link_refresh

#### Scenario: Probe invalidation stays separate

- **WHEN** an unrelated callback advances the diagnostic probe epoch
- **THEN** unchanged selected physical semantics emit no handover
- **AND** existing probe token invalidation still detects the event

#### Scenario: Two genuine link changes within the cooldown

- **WHEN** two distinct selected MTU or route changes occur within ten seconds
- **THEN** both changes can apply despite their equal stored scope keys
- **AND** a repeated identical applied signature remains suppressed

### Requirement: REQ-PHYSICAL-UNAVAILABLE — Unavailable evidence

The runtime MUST return no physical fingerprint for incomplete, blocked, lost
or ambiguous legacy observations. It MUST NOT invent a preferred network or
fall back to the application VPN. Existing loss and restore classifications
and safe tunnel defaults MUST remain explicit.

#### Scenario: No ready physical observation

- **WHEN** evidence is incomplete, blocked, lost or ambiguous on a legacy API
- **THEN** fingerprint capture is unavailable and the VPN overlay is not used
- **AND** later complete unambiguous evidence permits connectivity_restore

### Requirement: REQ-PHYSICAL-PRIVACY — Storage and DNS authority

The runtime MUST preserve the scopeKey recipe and policy serialization. Any
selected physical semantic token MUST be transient and independent of the
binder-issued direct-DNS generation. Raw identifiers MUST NOT enter logs or
artifacts. Physical selection alone MUST NOT grant direct-DNS authority.

#### Scenario: Runtime metadata does not change stored identity

- **WHEN** only a transient selected-path semantic token changes
- **THEN** scopeKey and serialized policy remain byte-identical
- **AND** no binder-issued DNS generation is manufactured

### Requirement: REQ-PHYSICAL-ACCEPTANCE — Source and device verification

The correction MUST pass source regressions, complete affected JVM suites,
independent review, genuine artifact preparation and the four authorized USB
scenarios. Earlier physical failures MUST remain recorded without an invented
physical-network cause. Hosted gates MUST pass before closure.

#### Scenario: Honest physical evidence

- **WHEN** the corrected source is tested on the owned physical device
- **THEN** source and APK digests identify each result and cleanup is checked
- **AND** failed or unverified required behavior keeps acceptance incomplete
