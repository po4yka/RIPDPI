## Purpose

Make connection choices, diagnostic evidence and live log events easy to read
and act on without changing the underlying network or persistence contracts.

## ADDED Requirements

### Requirement: REQ-UI2-CONNECTION — Explain the saved connection path

The app MUST show the saved traffic scope and path in one connection summary.
The primary start action MUST use the saved Mode. Settings navigation MUST NOT
appear to activate a path that it does not save. Existing relay activation,
VPN-consent recovery, pending and duplicate-action guards MUST be preserved.

#### Scenario: Inspect another settings section

- **GIVEN** a saved proxy mode and a different settings section is opened
- **WHEN** the user reads the summary and starts the connection
- **THEN** the summary describes proxy traffic and the action requests Proxy

#### Scenario: Start is rejected or pending

- **WHEN** a start is rejected or waits for service acknowledgement
- **THEN** its reason or pending state remains visible beside the main action
- **AND** recovery and duplicate-action behavior match the first-stage fixes

### Requirement: REQ-UI2-PROFILE — Keep the profile summary compact

The app MUST show the saved profile name, current path and change action before
long guidance or preset lists. Full profile choices and CLI precedence guidance
MUST remain available through explicit disclosure. An unsaved editor draft MUST
NOT be presented as the active saved profile.

#### Scenario: Read the default connection view

- **WHEN** the connection screen first opens
- **THEN** the saved profile and change action are visible without a long preset list
- **AND** the user can disclose all existing profile and advanced choices

### Requirement: REQ-UI2-DIAGNOSTICS — Show one evidence-bound outcome

Diagnostics MUST show one primary outcome with its own source, time, coverage
and confidence, or explicit unknown values. Active work MUST take precedence
over a completed outcome. The app MUST preserve stale, partial and failed states
and MUST NOT borrow a timestamp or recommendation from an unrelated profile or
session. Running service state or traffic bytes MUST NOT imply a successful scan.
Low-confidence recommendations MUST open choices or review, never auto-apply.

#### Scenario: Evidence sources belong to different sessions

- **GIVEN** the latest historical session and selected scan profile differ
- **WHEN** the overview displays an outcome
- **THEN** its metadata comes from the same selected evidence source
- **AND** other evidence remains clearly labelled as historical detail

#### Scenario: Partial or stale result

- **WHEN** a result is incomplete, failed or belongs to an older network context
- **THEN** the single outcome states that limit and offers a safe next action
- **AND** it does not present the result as verified current-network success

### Requirement: REQ-UI2-DISCLOSURE — Keep action meaning explicit

The app MUST identify the local diagnostics disclosure as technical details.
Changing that disclosure MUST NOT change the primary scan behavior. The visible
primary action label MUST describe the callback used for the persisted persona.

#### Scenario: Change technical detail visibility

- **WHEN** the user expands or hides technical details
- **THEN** the primary action keeps the same label and operation

### Requirement: REQ-UI2-LOGS — Give live events the screen area

Logs MUST use one vertical event list that fills the remaining screen height.
Compact controls MUST keep filtering, export, support sharing, clear and copy
available. Filters MUST be optional disclosure. Continuous source flows MUST
remain live; the screen MUST NOT show refresh progress without a reload operation.
New events MUST NOT move the viewport away from a user reading earlier entries.

#### Scenario: Read logs on a small screen

- **GIVEN** 360dp width and font scale 2.0 with matching log entries
- **WHEN** the screen opens with filters hidden
- **THEN** at least one event is visible and all controls remain reachable
- **AND** extra screen height increases the event list height

#### Scenario: Read older events while new entries arrive

- **WHEN** a user scrolls away from the live edge and another entry arrives
- **THEN** their viewport is retained until they explicitly return to the live edge

### Requirement: REQ-UI2-ACCESS — Preserve access and privacy

The changed controls MUST expose their name, safe value and action to semantics.
Light, dark, narrow and large-font layouts MUST keep actions reachable without
clipping. New strings MUST be provided in all ten supported locales. The change
MUST preserve offline use and MUST NOT expose secrets in summaries or semantics.

#### Scenario: Read settings with assistive semantics

- **WHEN** a user focuses a changed connection, diagnostics or log control
- **THEN** its name, current state and available action are unambiguous
- **AND** credentials and private keys are not included in its spoken value
