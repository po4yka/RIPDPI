## Purpose

Select usable encrypted relay paths using bounded authenticated payload transfers,
while preserving network lifecycle ownership and avoiding unstable retry loops.

## ADDED Requirements

### Requirement: REQ-PAYLOAD-IMPORT — Admit the explicit policy

The importer MUST validate and persist a versioned `ripdpi.payload_failover`
extension bound to the imported selector. It MUST reject malformed supplied
policies, unsupported versions, invalid bounds and controls with the same origin.
Ordinary bundles without the extension SHALL retain their URL-test policy.

#### Scenario: Coordinated bundle import

- **WHEN** a bundle contains two distinct HTTPS controls, exact body sizes and hashes, and valid bounded timing fields
- **THEN** its selector persists the complete policy without dropping fields

#### Scenario: Malformed policy cannot silently degrade

- **WHEN** a supplied policy contains an unknown field, selector mismatch or invalid payload digest
- **THEN** the entire import is rejected instead of silently using RTT checks

### Requirement: REQ-PAYLOAD-VERIFY — Prove the candidate payload

The client MUST fetch controls through the candidate relay using authenticated
HTTPS, without redirects or direct fallback. A successful control MUST return
HTTP 200, exactly the configured bounded byte count, EOF and the configured
SHA-256 digest. Either of the independent controls may establish path health.

#### Scenario: Small success response

- **WHEN** a control returns HTTP 200 or 204 with fewer bytes than configured
- **THEN** the candidate is not qualified by that response

#### Scenario: One control outage

- **WHEN** one control fails but the other returns the exact authenticated payload
- **THEN** the candidate remains usable without declaring the control failure censorship

### Requirement: REQ-PAYLOAD-STABILITY — Bound automatic switching

The selector MUST require configured consecutive failures and recovery successes,
apply a switch cooldown and bounded exponential retry backoff, and reset pending
observations after a network or policy change. It MUST bound each probe and pass,
and preserve cancellation and candidate cleanup ownership.

#### Scenario: Transient failure

- **WHEN** the active candidate fails fewer times than the threshold
- **THEN** automatic selection is unchanged

#### Scenario: Sustained failure and recovered alternate

- **WHEN** the active candidate reaches the failure threshold and an alternate reaches the recovery threshold outside cooldown
- **THEN** the selector switches through its existing guarded reload path

### Requirement: REQ-PAYLOAD-SAFETY — Preserve encrypted routing

The controller MUST never select direct egress, mutate user routing or bypass
manual-selection and network-generation fences. When all candidates fail it
SHALL retain the last encrypted selection. It MUST NOT log control URLs, bodies,
credentials or raw network identifiers.

#### Scenario: No healthy relay

- **WHEN** every candidate fails payload verification
- **THEN** no direct path is introduced and the current encrypted selection remains

#### Scenario: Network changes during probe

- **WHEN** the network scope changes while a candidate is measured
- **THEN** the stale result cannot switch the new network's selection
