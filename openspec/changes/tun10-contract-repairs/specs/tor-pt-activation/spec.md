## Purpose

Keep Tor bridge transports separate from generic relay egress and make managed helper startup and shutdown observable and bounded.

## ADDED Requirements

### Requirement: REQ-TUN10-EGRESS — Reject standalone PT activation

The implementation MUST reject standalone obfs4, Snowflake and WebTunnel before runtime activation, retain saved profile data, and retain Tor bridge transport selection.

#### Scenario: Open a legacy standalone profile

- **WHEN** a saved standalone PT profile is opened and activated
- **THEN** it remains readable, activation fails with guidance to use Tor, and the user can select Tor.

### Requirement: REQ-TUN10-ENV — Negotiate managed version and errors

The WebTunnel helper MUST select supported version 1 from the offered list and emit PT VERSION-ERROR or ENV-ERROR before a failed startup exits.

#### Scenario: Configure a managed helper

- **WHEN** a parent offers version 1 with other versions or omits a required environment value
- **THEN** the helper selects version 1 or reports the correct protocol error and exits unsuccessfully without a listener announcement.

### Requirement: REQ-TUN10-DEADLINE — Honor readiness deadlines

The PT runtime MUST bound readiness by the caller timeout and preserve cancellation and the original startup failure.

#### Scenario: Wait for a stalled startup

- **WHEN** readiness has not completed within the caller budget
- **THEN** the waiter terminates with a timeout instead of waiting indefinitely.

### Requirement: REQ-TUN10-CHANNEL — Keep diagnostic output separate

The managed PT controller MUST use stdout for control messages and drain stderr without allowing diagnostic text to change listener readiness.

#### Scenario: Receive PT diagnostics during startup

- **WHEN** stderr contains text that resembles a control message
- **THEN** it cannot complete managed initialization and both output streams continue to drain.

### Requirement: REQ-TUN10-STOP — Bound managed shutdown

The managed PT owner MUST close stdin before signal escalation, retain bounded termination waits, and retain ownership until exit is observed.

#### Scenario: Stop a helper

- **WHEN** a managed PT stops normally or ignores stdin closure
- **THEN** it either exits through stdin EOF or receives bounded escalation without an orphaned owned process.

### Requirement: REQ-TUN10-SCOPE — Preserve the transport boundary

The supported contract MUST distinguish Tor link transport from generic TCP half-close and retain existing argument, endpoint and lifecycle regressions.

#### Scenario: Assess helper support

- **WHEN** PT support or repair evidence is reported
- **THEN** it names the supported Tor path and does not claim unimplemented Lyrebird half-close or unobserved external interoperability.
