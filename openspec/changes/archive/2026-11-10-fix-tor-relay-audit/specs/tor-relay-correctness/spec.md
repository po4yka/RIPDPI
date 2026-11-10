## Purpose

Keep Tor names private and make relay startup, storage and shutdown reliable.

## ADDED Requirements

### Requirement: REQ-TOR-DNS — Dns

The implementation MUST preserve the target name through the Tor route without a direct DNS query.

#### Scenario: Dns regression

- **WHEN** a domain or onion target is sent to the main proxy
- **THEN** the Tor upstream receives the name and direct DNS receives no query.

### Requirement: REQ-TOR-BUDGET — Budget

The implementation MUST allow the first Tor dial to use its bootstrap budget.

#### Scenario: Budget regression

- **WHEN** bootstrap takes more than ten seconds within the allowed budget
- **THEN** the outer proxy continues waiting and Stop can still cancel it.

### Requirement: REQ-TOR-RETRY — Retry

The implementation MUST report bootstrap success only after a usable directory exists.

#### Scenario: Retry regression

- **WHEN** a timed-out bootstrap is retried while the bridge is unavailable
- **THEN** the retry remains pending or returns an error.

### Requirement: REQ-TOR-SHUTDOWN — Shutdown

The implementation MUST close stdin and reap a managed PT without waiting for stdout.

#### Scenario: Shutdown regression

- **WHEN** a quiet child is stopped
- **THEN** no child process remains after the bounded stop.

### Requirement: REQ-TOR-STATE — State

The implementation MUST assign different profile IDs distinct private state paths.

#### Scenario: State regression

- **WHEN** IDs collide under the old sanitizer or contain dot segments
- **THEN** state paths are distinct and existing unambiguous guards remain available.

### Requirement: REQ-TOR-VALIDATION — Validation

The implementation MUST reject empty and transport-invalid Tor bridge drafts.

#### Scenario: Validation regression

- **WHEN** a profile is saved with an invalid bridge
- **THEN** validation reports the bridge error before startup.
