## Purpose

Provide authoritative, runtime-correlated evidence that distinguishes the persisted Autolearn setting, the resolved session request, and the effective native state without inferring live behavior from an idle telemetry placeholder.

## ADDED Requirements

### Requirement: REQ-AUT-INITIAL-SNAPSHOT — Publish authoritative startup telemetry

The service MUST publish the authoritative proxy telemetry snapshot obtained after native readiness before observers can see the service as connected.

#### Scenario: Successful proxy start

- **GIVEN** the proxy runtime has reached native readiness and returned a telemetry snapshot
- **WHEN** the service publishes its connected state
- **THEN** the shared service telemetry already contains that snapshot with telemetry state `snapshot`

#### Scenario: Successful VPN start

- **GIVEN** the VPN composition uses the RIPDPI proxy path, has started its proxy runtime, and obtained the ready telemetry snapshot
- **WHEN** the VPN service publishes its connected state
- **THEN** diagnostics observing the same runtime see the authoritative proxy snapshot rather than an idle `NoData` placeholder

### Requirement: REQ-AUT-ACTIVATION-LAYERS — Record configured, resolved, and effective states

For every successful initial start or runtime replacement that creates a RIPDPI proxy runtime, the implementation MUST record one Autolearn activation receipt containing the persisted enabled state, the resolved/requested enabled state, the native-effective enabled state, the resolution source, mode, runtime ID, policy signature when available, outcome, and observation time. Provider-owned paths that do not create a RIPDPI proxy MUST NOT synthesize an Autolearn receipt.

#### Scenario: Baseline settings activate Autolearn

- **GIVEN** persisted settings enable Autolearn and baseline policy resolution requests it
- **WHEN** the ready native snapshot reports Autolearn enabled
- **THEN** the receipt reports persisted `enabled`, resolved `enabled`, effective `enabled`, source `baseline_settings`, and outcome `active`

#### Scenario: Remembered policy participates in resolution

- **GIVEN** an exact remembered-network policy is used to resolve the runtime configuration
- **WHEN** the runtime becomes ready
- **THEN** the receipt identifies source `remembered_policy` while retaining all three enabled-state layers

#### Scenario: Command-line configuration is authoritative

- **GIVEN** command-line settings mode is enabled
- **WHEN** the runtime becomes ready
- **THEN** the receipt identifies source `command_line` and records the resolved and native-effective states without serializing command-line contents

#### Scenario: Alternate provider owns the VPN runtime

- **GIVEN** an alternate provider handles VPN startup without creating a RIPDPI proxy runtime
- **WHEN** the provider reports successful startup
- **THEN** no Autolearn activation receipt is emitted for that provider-owned runtime

### Requirement: REQ-AUT-MISMATCH — Classify activation divergence explicitly

The implementation MUST classify disagreement between the resolved/requested enabled state and the ready native-effective enabled state as a mismatch instead of reporting either state as authoritative for the other layer.

#### Scenario: Native state differs from the request

- **GIVEN** the resolved runtime request enables Autolearn
- **WHEN** the ready native snapshot reports Autolearn disabled
- **THEN** the receipt outcome is `mismatch` and both differing values remain visible

### Requirement: REQ-AUT-UNAVAILABLE — Preserve telemetry unavailability

The implementation MUST represent a missing or failed authoritative telemetry read as unavailable or failed evidence and MUST NOT synthesize effective `disabled` or zero counters from an idle snapshot.

#### Scenario: Startup telemetry cannot be obtained

- **GIVEN** the native runtime does not provide the authoritative snapshot required to resolve its ready listener
- **WHEN** startup is finalized
- **THEN** startup fails through the existing typed startup-failure path and no successful activation receipt is emitted

#### Scenario: Later telemetry polling fails

- **GIVEN** a successful activation receipt exists for the runtime
- **WHEN** a later telemetry poll returns `EngineError`
- **THEN** diagnostics retain the activation receipt and separately report the later telemetry error

### Requirement: REQ-AUT-DURABILITY — Retain evidence for short runtimes and replacements

The implementation MUST synchronously attempt to persist the activation receipt through the existing diagnostics artifact store before publishing `Connected`, so that a short-lived runtime can still be diagnosed after it stops. A diagnostics-storage failure MUST be reported as a persistence warning and MUST NOT prevent the network runtime from starting.

#### Scenario: Runtime stops before the periodic telemetry loop

- **GIVEN** receipt persistence succeeds and the runtime becomes ready and then stops before the first periodic telemetry interval
- **WHEN** a diagnostics archive is exported
- **THEN** the archive still contains the activation receipt correlated to that runtime ID

#### Scenario: Receipt storage fails

- **GIVEN** the native runtime is ready but the diagnostics artifact store rejects the activation receipt
- **WHEN** startup continues
- **THEN** the service can still become connected and the failure is surfaced as a diagnostics persistence warning rather than a network startup failure

#### Scenario: Network handover replaces the runtime

- **GIVEN** a connected service replaces its proxy runtime after a network handover
- **WHEN** the replacement runtime becomes ready
- **THEN** a distinct activation receipt is recorded for the replacement generation without overwriting the earlier receipt

### Requirement: REQ-AUT-PRIVACY-COMPAT — Preserve privacy and existing contracts

The receipt MUST use the existing native-session event storage and export envelope and MUST NOT contain host names, store paths, raw network identifiers, command-line text, or other user payloads.

#### Scenario: Archive export redacts correlation data

- **GIVEN** an activation receipt contains runtime and policy correlators
- **WHEN** the diagnostics archive redactor processes the event
- **THEN** correlators follow the existing redaction rules and the receipt retains only coarse Autolearn state and source tokens

#### Scenario: Existing archive consumer reads the new event

- **GIVEN** a consumer that understands the current native-session event envelope
- **WHEN** it reads an archive containing an Autolearn activation receipt
- **THEN** it can ignore the new event kind without a JNI, protobuf, Room, or archive-schema migration
