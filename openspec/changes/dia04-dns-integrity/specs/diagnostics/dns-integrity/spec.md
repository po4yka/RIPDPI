## Purpose

Keep DNS integrity verdicts, response facts, and CDN ECH configuration consistent with the observed network exchange.

## ADDED Requirements

### Requirement: REQ-DIA04-ORACLE — Independent resolver agreement

One resolver service MUST contribute at most one independent agreement vote, including aliases and supported protocols.

#### Scenario: Verify independent resolver agreement

- **WHEN** two endpoint aliases identify the same service
- **THEN** the aliases MUST NOT establish independent quorum

### Requirement: REQ-DIA04-OVERLAP — Mixed answer safety

A shared address or prefix MUST NOT hide an additional sinkhole address from DNS integrity classification.

#### Scenario: Verify mixed answer safety

- **WHEN** a UDP answer contains a shared public address and an extra loopback address
- **THEN** the result MUST NOT be a clean match

### Requirement: REQ-DIA04-CDN-CONSUMER — Current CDN configuration

The real CDN fallback consumer MUST use the current refreshed or restored valid configuration.

#### Scenario: Verify current cdn configuration

- **WHEN** the updater contains valid configuration B after refresh or restore
- **THEN** fallback construction MUST use B instead of the bundled configuration A

### Requirement: REQ-DIA04-PROTECT — Protected CDN refresh

Direct CDN refresh MUST protect each non-loopback socket when protection is active and MUST fail closed when protection fails.

#### Scenario: Verify protected cdn refresh

- **WHEN** socket protection rejects a direct refresh connection
- **THEN** no unprotected connection MUST be attempted

### Requirement: REQ-DIA04-EVIDENCE — Response attribution

Reported answer facts and endpoint metadata MUST refer to the same observed attempt. A report without oracle selection MUST label the observed response role explicitly. Typed DNS address arrays MUST contain only valid IP addresses; error strings MUST remain in status or details.

#### Scenario: Verify response attribution

- **WHEN** the primary fails and fallback B is selected
- **THEN** answer metadata MUST identify B and retain the primary attempt separately

#### Scenario: Keep error text out of address arrays

- **WHEN** a current address detail is an error that contains an IP-like token after a delimiter
- **THEN** its typed address array MUST be empty and MUST NOT use a stale legacy address field

### Requirement: REQ-DIA04-COMPRESSION — Valid compression chains

Bounded acyclic backward DNS compression chains MUST NOT be classified as malformed. Real loops and out-of-bounds pointers MUST remain rejected.

#### Scenario: Verify valid compression chains

- **WHEN** a valid name follows four backward compression pointers
- **THEN** the malformed pointer signal MUST be false

### Requirement: REQ-DIA04-EDE — Retained diagnostic EDE

Diagnostic DNS exchanges MUST retain bounded numeric EDE values that the responder returned, without retaining extra text.

#### Scenario: Verify retained diagnostic ede

- **WHEN** a local binary DNS responder returns EDE code 15 in an ordinary response
- **THEN** the diagnostic semantic evidence MUST contain code 15

### Requirement: REQ-DIA04-ECH-ADMISSION — Valid persisted ECH data

Persisted ECH admission MUST parse the complete list and require a supported entry while allowing unknown entries before a supported entry.

#### Scenario: Verify valid persisted ech data

- **WHEN** the list declares a version but omits its inner length and content
- **THEN** admission MUST fail without making the cache fresh
