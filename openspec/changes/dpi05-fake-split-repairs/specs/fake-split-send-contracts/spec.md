## Purpose

Define safe fake split sends and independent acceptance without expanding protocol or permission scope.

## ADDED Requirements

### Requirement: REQ-DPI05-PROGRESS — Preserve send progress

The implementation MUST restore real queued pages before normal cleanup. Preserve known progress and uncertain delivery; refuse retry after possible send.

#### Scenario: Preserve send progress

- **WHEN** a write, wait or delivered helper operation fails
- **THEN** queued bytes are real and retry is refused after possible delivery.

### Requirement: REQ-DPI05-PROFILES — Preserve accepted profiles

The implementation MUST preserve primary then secondary in every fake group for all supported orders and sequence modes.

#### Scenario: Preserve accepted profiles

- **WHEN** a secondary TLS profile uses a custom order
- **THEN** both profiles remain and genuine offsets are unchanged.

### Requirement: REQ-DPI05-MD5 — Keep MD5 fake-only

The implementation MUST restrict synthetic MD5 to fake packets in ordered and raw-original sends.

#### Scenario: Keep MD5 fake-only

- **WHEN** MD5 fooling is enabled
- **THEN** genuine segments contain no synthetic MD5 option.

### Requirement: REQ-DPI05-TTL — Preserve genuine TTL

The implementation MUST keep genuine raw originals at normal TTL, separate from fake TTL.

#### Scenario: Preserve genuine TTL

- **WHEN** original flag overrides require raw sending
- **THEN** genuine TTL remains normal.

### Requirement: REQ-DPI05-ORACLE — Verify complete wire evidence

The implementation MUST require explicit source bytes and independent checks of packet ranges, order, sequence, TTL and options for full raw acceptance.

#### Scenario: Verify complete wire evidence

- **WHEN** bytes or order are wrong or a range is missing
- **THEN** full acceptance fails; incomplete stream captures remain smoke-only.

### Requirement: REQ-DPI05-FAMILY — Keep error context

The implementation MUST keep family and fallback context consistent for both fake split regions.

#### Scenario: Keep error context

- **WHEN** the second region fails
- **THEN** its error retains the recognized family and prior progress.
