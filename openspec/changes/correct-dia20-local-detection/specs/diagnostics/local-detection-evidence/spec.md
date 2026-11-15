## ADDED Requirements

### Requirement: REQ-DIA20-TLS Local TLS outcome and coverage

The checker SHALL report a local enabled provider configuration and its coverage of the fixed cipher set. It SHALL identify a preset ID as a setting and SHALL keep local TLS findings out of VPN routing evidence.

#### Scenario: Valid TLS configuration

- **WHEN** the provider returns nonempty enabled cipher and protocol lists
- **THEN** the checker reports coverage and a hash of those lists without asserting network or browser identity

#### Scenario: Unavailable provider

- **WHEN** TLS configuration is unavailable
- **THEN** the checker reports an incomplete or error outcome without a hash or positive detection

### Requirement: REQ-DIA20-APP Installed app evidence and ownership

The assessment SHALL separate installed candidates from active owner evidence. Name-only candidates SHALL remain local review clues and SHALL NOT contribute to positive detection or a score penalty.

#### Scenario: Package A is installed and package B is active

- **WHEN** independent evidence identifies B as active
- **THEN** the owner row identifies B and the local inventory can still list A

#### Scenario: Label-only candidate

- **WHEN** an app label is the only evidence for a package
- **THEN** the candidate remains visible for review without setting a positive detection axis

### Requirement: REQ-DIA20-INVENTORY Bounded, scoped inspection

The detector SHALL bound package and APK inspection, check cancellation and distinguish partial or unavailable data from no visible match. Archive filename clues SHALL identify their source and SHALL NOT assert a binary or Go version.

#### Scenario: One APK split cannot be read

- **WHEN** a package is identified but an APK split cannot be inspected
- **THEN** the detector retains the package evidence and identifies the inspection as partial

### Requirement: REQ-DIA20-EXPORT Private export and TLS result parity

Private exports SHALL omit installed-app identities and derived details. Report formats SHALL preserve the TLS category and distinguish skipped from failed.

#### Scenario: Share a private report

- **WHEN** the result contains an installed or active VPN app identity
- **THEN** private report fields and text omit its name, package, service, version and related metadata

#### Scenario: TLS is disabled or fails

- **WHEN** the TLS check is skipped or fails
- **THEN** the reports distinguish the two outcomes
