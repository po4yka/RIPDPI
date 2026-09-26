## Purpose

The document picker must complete the diagnostics archive selected before Android recreates the Activity.

## ADDED Requirements

### Requirement: REQ-ARCHIVE-RECREATION — Preserve a pending archive export

The app MUST write the originally requested diagnostics archive to the URI returned by the document picker after Activity recreation.

#### Scenario: Picker returns after recreation

- **WHEN** Android recreates the Activity while the document picker is open and the picker returns a document URI
- **THEN** the app writes the original diagnostics archive to that document once

#### Scenario: Picker cancellation after recreation

- **WHEN** Android recreates the Activity while the document picker is open and the picker returns no URI
- **THEN** the app clears the pending export without writing an archive
