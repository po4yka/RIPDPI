## Purpose

Admit explicitly opted-in transfer-quality controls without weakening existing
authenticated payload fallback or creating a second profile selector.

## MODIFIED Requirements

### Requirement: REQ-PAYLOAD-IMPORT — Admit the explicit policy

The importer MUST validate and persist schema 2 of `ripdpi.payload_failover`,
bound to the imported selector. It MUST reject unsupported versions, unknown
fields, invalid bounds, selector mismatch and controls with the same origin
before any profile mutation. Objects MUST contain 32,768 through 1,048,576 bytes.
Optional `quality` MUST contain exactly `max_pass_bytes`, `max_window_bytes`,
`window_seconds`, `minimum_gain_percent` and `maximum_loaded_latency_increase_ms`.
Pass capacity MUST cover twice the sum of both expected body sizes plus one
overrun byte per body; window capacity MUST cover the pass, and the window MUST
cover the policy interval. Ordinary bundles without the extension SHALL retain
URLTest behavior; schema-2 policies without quality SHALL retain payload-health
fallback and SHALL NOT launch quality measurements.

#### Scenario: Coordinated schema-2 import

- **WHEN** the producer emits a valid optional quality policy with two HTTPS controls
- **THEN** the complete selector policy persists without dropping quality fields

#### Scenario: Unsupported or undersized policy

- **WHEN** schema 1, an unknown quality field or an insufficient pass budget is supplied
- **THEN** import fails atomically, including any AWG entries in the same source

#### Scenario: Quality is absent

- **WHEN** a valid schema-2 payload policy has no quality object
- **THEN** only the existing payload-health behavior is enabled

#### Scenario: Coordinated bundle import

- **WHEN** a bundle contains two distinct HTTPS controls, exact body sizes and hashes, and valid bounded timing fields
- **THEN** its selector persists the complete policy without dropping fields

#### Scenario: Malformed policy cannot silently degrade

- **WHEN** a supplied policy contains an unknown field, selector mismatch or invalid payload digest
- **THEN** the entire import is rejected instead of silently using RTT checks

#### Scenario: Malformed policy alongside AWG profiles

- **WHEN** a refresh includes AWG entries and an invalid payload policy
- **THEN** it fails before any AWG or relay persistence, without alternate-format or AWG-only fallback
