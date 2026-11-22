## ADDED Requirements

### Requirement: REQ-OUT-1795326656806182-001 — Use the local result for the current member

The selector SHALL measure its current automatic member despite a fresh cloud down status, subject to the existing route and probe rules.

#### Scenario: The current transfer succeeds

- **WHEN** the current member reports down and completes its local control transfer
- **THEN** cloud health alone causes no switch or down reserve probe

### Requirement: REQ-OUT-1795326656806182-002 — Admit one eligible down reserve after local failures

When the current and ordinary candidates have failed, the selector SHALL allow one eligible down reserve to use the existing reserve slot. Budget, scope, and confirmation checks SHALL apply to this probe and any later confirmation.

#### Scenario: The reserve slot is unavailable

- **WHEN** the ordinary candidates fail and the shared payload budget denies the next pass
- **THEN** the selector starts no additional probe
