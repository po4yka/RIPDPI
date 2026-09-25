## Purpose

Diagnostic rows must expose only actions that can show useful details, so users can identify available navigation and information correctly.

## ADDED Requirements

### Requirement: REQ-DIAGNOSTIC-ROW-SEMANTICS — Diagnostic row semantics match behavior

The app MUST omit click semantics from diagnostic rows without a detail action and MUST retain click semantics for rows with a working detail action.

#### Scenario: Informational row

- **WHEN** an event, session, probe, or warning has no detail action in its current context
- **THEN** the row is presented as information without a click action

#### Scenario: Detail row

- **WHEN** a session or event has a detail callback
- **THEN** the row is presented as clickable and invokes that callback
