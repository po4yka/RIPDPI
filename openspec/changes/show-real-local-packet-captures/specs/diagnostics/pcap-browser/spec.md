## Purpose

The packet capture browser lets a user inspect only completed captures recorded locally by this device after explicit capture activation.

## ADDED Requirements

### Requirement: REQ-DGN-1790329640901759-001 — List recorded captures

The app MUST list completed captures from its private PCAP directory and MUST show an empty state when no captures exist.

#### Scenario: Captures exist

- **WHEN** the user opens the capture list after recording completes
- **THEN** the list shows metadata for the recorded files, not demonstration files

#### Scenario: No captures exist

- **WHEN** the private PCAP directory contains no completed captures
- **THEN** the list shows its empty state

### Requirement: REQ-DGN-1790329640901759-002 — Inspect the selected capture

The app MUST show packet bytes read from the selected capture and MUST keep raw packet data local unless the user explicitly exports it.

#### Scenario: Select a capture

- **WHEN** the user selects a completed capture
- **THEN** the viewer identifies that file and shows its recorded packets and bytes

### Requirement: REQ-DGN-1790329640901759-003 — Reject unavailable or unsafe selections

The app MUST reject a missing, unreadable, or out-of-directory capture selection without reading an unrelated private file.

#### Scenario: Invalid selection

- **WHEN** the viewer receives an unsafe or unavailable file name
- **THEN** it shows an error and does not display demonstration packets or unrelated file contents
