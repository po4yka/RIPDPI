## Purpose

Define a user-invoked, isolated relay preflight that checks whether a parsed profile can reach a controlled TCP target before import without changing durable or active application state.

## ADDED Requirements

### Requirement: REQ-PREFLIGHT-ACTION — Expose an explicit pre-import check

The implementation MUST expose a localized, accessible `Check profile` action on the single-profile import-confirmation screen when the parsed profile is supported by the native relay runtime.

#### Scenario: Supported profile is ready to check

- **GIVEN** a relay-activatable profile has been parsed and is displayed on the import-confirmation screen
- **WHEN** no import or preflight is in progress and the application service is halted
- **THEN** the screen enables a text-labeled `Check profile` action with a minimum 48 dp touch target
- **AND** the existing Add action remains a separate explicit operation

#### Scenario: Unsupported profile cannot start a preflight

- **GIVEN** a parsed profile kind cannot be mapped to a native relay runtime
- **WHEN** the import-confirmation screen is displayed
- **THEN** the preflight action is unavailable
- **AND** the screen does not describe the profile as checked or validated

### Requirement: REQ-PREFLIGHT-SERVICE-ISOLATION — Do not compete with active service lifecycle

The implementation MUST fail closed before opening an outbound relay socket unless the authoritative service status is `Halted`, and it MUST NOT stop, restart, reconfigure, or otherwise interfere with an active VPN or proxy service.

#### Scenario: Service is running or reconnecting

- **GIVEN** the service status is any value other than `Halted`
- **WHEN** the user attempts to check a profile
- **THEN** no temporary runtime or probe is created
- **AND** the UI reports that the service must be stopped before checking
- **AND** the existing service lifecycle and VPN protection callback are left unchanged

#### Scenario: Service starts during the preflight admission boundary

- **GIVEN** the service was halted when the screen rendered
- **WHEN** the service leaves `Halted` before the preflight acquires its execution guard
- **THEN** the preflight fails closed without starting the relay runtime

### Requirement: REQ-PREFLIGHT-EPHEMERAL-RUNTIME — Use one bounded temporary relay session

The implementation MUST create a session-local relay configuration from the parsed profile, bind its local SOCKS listener to loopback on an ephemeral port, and execute exactly one bounded TCP egress probe without enabling retries or failover.

#### Scenario: Profile reaches the controlled target

- **GIVEN** a supported profile and a halted service
- **WHEN** the temporary relay becomes ready and the single TCP probe receives an accepted HTTP response from the configured controlled target before the deadline
- **THEN** the preflight reports success for that observed relay-to-target path
- **AND** no UDP probe, alternate profile, failover candidate, or retry is executed

#### Scenario: Relay or probe exceeds the deadline

- **GIVEN** the temporary relay does not become ready or the TCP probe does not finish within the preflight deadline
- **WHEN** the deadline expires
- **THEN** the preflight reports a timeout outcome
- **AND** it does not start another attempt

### Requirement: REQ-PREFLIGHT-NON-MUTATION — Preserve durable and active configuration

The implementation MUST NOT persist, select, activate, import, or quarantine the checked profile and MUST NOT mutate profile groups, relay profile records, relay credentials, app settings, failover memory, or service state.

#### Scenario: Check completes on any terminal path

- **GIVEN** snapshots of all profile, credential, group, settings, failover, and service state before the check
- **WHEN** the preflight succeeds, fails, times out, or is cancelled
- **THEN** all snapshots are byte-for-byte or semantically equivalent to their preflight values
- **AND** no imported event or activation side effect is emitted

### Requirement: REQ-PREFLIGHT-CLEANUP — Clean up on every exit path

The implementation MUST request stop and await termination of the temporary runtime in a bounded non-cancellable cleanup section after success, startup failure, probe failure, timeout, caller cancellation, or screen/ViewModel disposal.

#### Scenario: Screen leaves while probe is pending

- **GIVEN** a temporary runtime is ready and its TCP probe is pending
- **WHEN** the import-confirmation owner is cleared or the check coroutine is cancelled
- **THEN** the probe is cancelled
- **AND** the temporary runtime receives one stop request and its job is joined
- **AND** no native handle, listener, or preflight job remains active

#### Scenario: Cleanup cannot finish before its own deadline

- **GIVEN** a temporary runtime does not terminate after stop is requested
- **WHEN** the cleanup deadline expires
- **THEN** the runtime job is cancelled and joined using the repository lifecycle fallback
- **AND** the UI does not report success

### Requirement: REQ-PREFLIGHT-TRUTHFUL-RESULT — Report observed evidence without overclaiming

The implementation MUST model idle, checking, success, unsupported, service-busy, startup/readiness failure, probe failure, timeout, and cancelled outcomes as typed state and MUST qualify the scope of every user-visible conclusion.

#### Scenario: Successful TCP path check

- **GIVEN** the single preflight TCP probe succeeded
- **WHEN** the result is rendered
- **THEN** the message states that the profile reached the test target during this check
- **AND** it does not state or imply that the profile is imported, selected, fully validated, or that VPN/TUN traffic works

#### Scenario: Relay handshake fails

- **GIVEN** the temporary relay cannot complete startup or readiness
- **WHEN** the result is rendered
- **THEN** the UI reports a neutral profile-check failure category
- **AND** it does not claim a server, network, censorship, credential, or profile root cause that was not directly established

### Requirement: REQ-PREFLIGHT-PRIVACY — Keep profile material out of observable diagnostics

The implementation MUST keep endpoints, UUIDs, passwords, keys, short IDs, SNI values, and raw native exception messages out of UI strings, application logs, analytics, and preflight result state.

#### Scenario: Native failure contains profile material

- **GIVEN** a runtime exception includes an endpoint and credential-bearing profile fields
- **WHEN** the failure is projected to UI state or logs
- **THEN** only an allowlisted failure category is retained
- **AND** none of the profile material is observable

### Requirement: REQ-PREFLIGHT-COMPATIBILITY — Preserve existing import and runtime contracts

The implementation MUST preserve the existing Add/import behavior and SHALL use existing relay runtime and probe contracts without changing JNI, wire-schema, protobuf, database, or migration contracts.

#### Scenario: User imports without checking

- **GIVEN** a supported parsed profile on the import-confirmation screen
- **WHEN** the user selects Add without running a preflight
- **THEN** the existing validation, persistence, activation, rollback, and navigation behavior remains unchanged

#### Scenario: User imports after a failed check

- **GIVEN** a preflight has produced a non-success outcome
- **WHEN** the user explicitly selects Add
- **THEN** the existing import flow remains available and independently validates and activates the profile
- **AND** the previous preflight outcome is not treated as an activation receipt
