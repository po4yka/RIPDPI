## Purpose

Keep late Home network measurements and their conclusions within one observed physical network scope.

## ADDED Requirements

### Requirement: REQ-HOME-NETWORK-SCOPE — Reject mixed network authority

Home MUST capture a transient physical epoch and fingerprint at admission and validate them before and after network augmentation. Missing or changed scope MUST suppress network measurements, network recommendations, and cache authority while local routing and detector catalog results remain available. Scope tokens MUST NOT enter persistence or exports. A regression baseline MUST have the same non-null network fingerprint as the current run.

#### Scenario: Network changes during DNS measurement

- **WHEN** the physical epoch changes while DNS augmentation runs, including A-B-A
- **THEN** the completed result has no network augmentation or recommendation authority and retains local evidence

#### Scenario: Scope is stable

- **WHEN** the original epoch and fingerprint remain current
- **THEN** network augmentation remains available

#### Scenario: Scope evidence is unavailable

- **WHEN** the original physical epoch cannot be captured
- **THEN** network augmentation is not used

#### Scenario: Previous run belongs to a different network

- **WHEN** the previous completed run has a different or unknown network fingerprint
- **THEN** it does not produce a regression comparison for the current run

### Requirement: REQ-HOME-CAPTIVE-UNKNOWN — Preserve missing capability evidence

The network character result MUST leave captive portal state unknown when network capabilities are unavailable.

#### Scenario: Missing capabilities

- **WHEN** the capabilities query has no result
- **THEN** captive portal detection is null
