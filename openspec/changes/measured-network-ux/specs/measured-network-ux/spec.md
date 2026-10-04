## Purpose

Make network measurements and connection actions understandable while preserving factual evidence, local privacy and durable user intent.

## ADDED Requirements

### Requirement: REQ-SCOPE — Explain scan measurement scope

The app MUST localize direct/raw and active/in-path scope labels and explain actual lifecycle effects before a scan and on its result, while retaining machine-readable path contracts.

#### Scenario: Direct measurement

- **WHEN** a user selects a direct-path scan
- **THEN** the UI explains interruption of an active VPN before probing and only promises restoration supported by that workflow.

#### Scenario: Active path unavailable

- **WHEN** there is no eligible active runtime
- **THEN** in-path scanning has a factual unavailable reason rather than implying universal reachability.

### Requirement: REQ-METRICS — Explain measured metrics and freshness

The app MUST associate supported metrics with units, short consequences, applicable aggregation/sample window and evidence age. It MUST distinguish absent, partial, fresh and stale evidence without invented scores or samples.

#### Scenario: Empty and stale metrics

- **WHEN** telemetry is unavailable or old
- **THEN** the screen preserves no-data/partial states and labels old evidence instead of presenting it as current health.

### Requirement: REQ-CONFIG — Show factual configuration and recovery

The app MUST distinguish saved settings from the currently applied runtime configuration, show factual lifecycle states, and offer relevant repair or reconnect actions with truthful consequences.

#### Scenario: Configuration changes while connected

- **WHEN** saved configuration differs from the active runtime snapshot
- **THEN** the UI identifies the unapplied change; cancelling a reconnect preserves coherent saved and running state.

#### Scenario: Permission or startup failure

- **WHEN** activation is denied, cancelled, starting, stopping or failed
- **THEN** state and guidance describe the actual condition and do not claim protection or successful connectivity.

### Requirement: REQ-SEARCH — Search grouped profiles

The app MUST search actual diagnostic and saved relay profiles with meaningful existing group labels, preserve selection on dismissal and expose an accessible empty-filter state.

#### Scenario: Search and dismiss

- **WHEN** the user searches, filters or closes a profile picker without selecting
- **THEN** the prior selection remains and no runtime change occurs.

### Requirement: REQ-EXPORT — Preview and deliberately export evidence

The app MUST present export scope and supported redaction before diagnostic summary/archive share or save, default to privacy-preserving redaction, and surface preparation failure without launching export.

#### Scenario: Cancel or preparation failure

- **WHEN** the user cancels preview or export preparation fails
- **THEN** no share/save is launched and any sensitive preview state is cleared or retained only for an explicit retry as appropriate.

#### Scenario: Redacted export

- **WHEN** the user confirms a redacted export
- **THEN** both preview and actual output use the selected redaction policy; raw endpoint identifiers are not silently included.

### Requirement: REQ-PAUSE — Persist timed connection pause intent

The app MUST allow an active eligible connection to pause for a chosen bounded duration, preserve a local resume intent before stopping, display its real deadline, and safely resume or explain a failed resume after process interruption. Explicit disconnect/cancel MUST invalidate pending resume. It MUST respect revoked VPN consent and operating-system background limits.

#### Scenario: Process interruption

- **WHEN** the app process restarts with a persisted unexpired pause
- **THEN** the countdown and resume scheduling are restored from local state without fabricating an active connection.

#### Scenario: Deadline or cancellation

- **WHEN** the deadline arrives or the user explicitly cancels the pause
- **THEN** the scheduler atomically consumes or invalidates that intent and never restarts a connection the user cancelled.

### Requirement: REQ-PROFILES — Persist useful selections and measure automatic choice

The app MUST persist favorite and successfully used recent relay profiles locally, and offer automatic selection using successful actual payload URL-tests with a visible measurement basis. It MUST handle no profiles, failed/unavailable/stale measurements and deleted profiles honestly, without synthetic latency or runtime events.

#### Scenario: Successful measured choice

- **WHEN** the user requests a measured automatic choice
- **THEN** eligible actual profiles are probed through the existing protected payload probe contract and selection is based on successful observed latency.

#### Scenario: Failed measurement or deleted profile

- **WHEN** all measurements fail or a saved profile is deleted
- **THEN** the current valid selection is preserved, unavailable choice is explained, and favorite/recent metadata does not resurrect the deleted profile.

### Requirement: REQ-ACCESSIBILITY — Preserve Android presentation and privacy

The implementation MUST use existing RDS tokens, support all ten locales, readable light/dark and large-font/RTL layouts, accessible actions and Android Back/dismiss semantics. Persisted state MUST remain app-private and excluded from system backup; exported sensitive information MUST remain user-controlled.

#### Scenario: Large font and RTL

- **WHEN** the new controls are rendered at accessibility font scale or in RTL
- **THEN** labels, consequences, selection and actions remain readable and operable without relying on color alone.
