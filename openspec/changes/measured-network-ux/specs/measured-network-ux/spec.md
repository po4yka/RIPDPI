## Purpose

Make network measurements and connection actions understandable while preserving factual evidence, local privacy and durable user intent.

## ADDED Requirements

### Requirement: REQ-SCOPE — Explain scan measurement scope

The app MUST localize direct/raw and active/in-path scope labels and explain actual lifecycle effects before a scan and on its result, while retaining machine-readable path contracts.

#### Scenario: Direct measurement

- **WHEN** a user selects a direct-path scan
- **THEN** the UI explains interruption of an active RIPDPI VPN or proxy before probing and only promises restoration supported by that workflow.

#### Scenario: Active path unavailable

- **WHEN** there is no eligible active RIPDPI runtime or its VPN route/local proxy listener is unavailable
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
- **THEN** preview and actual output use the fixed supported redacted/unlinkable policy; raw endpoint identifiers are not silently included.

#### Scenario: Prepared snapshot changes or delayed handoff

- **WHEN** source data changes after preview, a picker is recreated, or a receiving application reads the shared URI later
- **THEN** confirmation launches at most once with the exact prepared bytes and summary; a checked managed lease preserves eligible handoff files across process restart until its bounded expiry.

#### Scenario: Export clock eligibility

- **WHEN** the same-boot elapsed lifetime reaches three days, the observed wall clock moves backwards, or boot identity changes or cannot be verified
- **THEN** the old prepared lease cannot be consumed or opened; no clock adjustment extends its lifetime, and a new export can be prepared when the required clock proof is available.

#### Scenario: Replaced or late preparation

- **WHEN** preview is cancelled, replaced or cleared while preparation completes late
- **THEN** the stale generation cannot launch export, owned artifact/record cleanup is serialized, and cancellation or cleanup failure is preserved.

### Requirement: REQ-PAUSE — Persist timed connection pause intent

The app MUST allow an active eligible connection to pause for a chosen bounded duration, preserve a local resume intent before stopping, display its real deadline, and safely resume or explain a failed resume after process interruption. Explicit disconnect/cancel MUST invalidate pending resume. It MUST respect revoked VPN consent and operating-system background limits.

#### Scenario: Process interruption

- **WHEN** the app process restarts with a persisted unexpired pause
- **THEN** the countdown and resume scheduling are restored from local state without fabricating an active connection.

#### Scenario: Saved edits while paused

- **WHEN** the user edits ordinary saved settings or profiles during a pending pause
- **THEN** the pause remains and resumes its chosen mode using current saved configuration. Explicit Start/Stop, activation/deletion and reset supersede the pending intent.

#### Scenario: Deadline or cancellation

- **WHEN** the deadline arrives or the user explicitly cancels the pause
- **THEN** cancellation atomically invalidates that intent; deadline delivery starts only a matching eligible lease, and the intent is cleared only after a positive matching applied-runtime acknowledgement. A stale callback never restarts a newer or cancelled intent, and Android-delayed or denied recovery is displayed honestly.

#### Scenario: Checked pause and cleanup

- **WHEN** pause persistence or native cleanup fails
- **THEN** an unpersisted request keeps the active runtime; cleanup-pending never claims Paused. Paused foreground reconstruction creates no native/TUN/protect/selector/probe resources.

#### Scenario: Journal recovery and newer intent

- **WHEN** profile mutation recovery runs across a process interruption or legacy journal migration
- **THEN** required typed provenance and a durable expected-generation fence protect the current intent before replay. Recovery and compensation cannot invalidate or resurrect a newer pause; unknown legacy provenance is not silently defaulted.

#### Scenario: Explicit resume after clock or boot change

- **WHEN** automatic timing proof is unavailable after a clock or boot change
- **THEN** automatic recovery remains deferred, while an eligible explicit Resume now action can resume the recorded chosen mode using current settings without declaring the old deadline valid. Only its positive matching applied acknowledgement clears the pending pause.

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
