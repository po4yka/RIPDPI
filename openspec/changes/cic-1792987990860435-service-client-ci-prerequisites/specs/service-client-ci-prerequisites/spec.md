## Purpose

Restore the release build and UI regression checks required to integrate the service contract mirror.

## ADDED Requirements

### Requirement: REQ-SCCP-BUILD — Compile the asynchronous WebTunnel library

WebTunnel MUST declare the Tokio features used by its library in normal dependencies, so a locked non-test library build compiles without dev-feature unification.

#### Scenario: Build release library without dev dependencies

- **WHEN** the existing WebTunnel library is checked without test dependencies
- **THEN** its `tokio::select!` call compiles with the locked dependency graph.

### Requirement: REQ-SCCP-UI — Verify current Simple Home controls

Simple Home tests MUST verify the current confirmation dialog before disconnect, keep exact callback counts, and verify share/save availability independently of label case.

#### Scenario: Confirm a connected session disconnect

- **WHEN** the enabled actuator is pressed and the dialog confirmation is selected
- **THEN** the disconnect callback runs once.

#### Scenario: Keep save available during an active share

- **WHEN** a completed report is sharing
- **THEN** the share action is disabled and the save action remains enabled and dispatches once.

#### Scenario: Keep completed and failed reports shareable

- **WHEN** a completed or failed report has an archive and sharing is idle
- **THEN** the share action remains enabled and the completed-report test verifies its callback.
