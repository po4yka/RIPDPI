## Purpose

Define the options used when a manual scan starts after resolving a hidden automatic probe conflict.

## ADDED Requirements

### Requirement: REQ-DGN-1790432046570971-001 — Preserve pending manual scan options

The implementation MUST use the original manual scan deadline, candidate limit, target overrides, owner, and raw-path resume policy after conflict resolution.

#### Scenario: Wait for the hidden probe

- **WHEN** a manual scan has a hidden probe conflict and the user chooses WAIT after the probe ends
- **THEN** the scan starts with the original profile, settings, path mode, deadline, candidate limit, target overrides, owner, and resume policy

#### Scenario: Cancel the hidden probe

- **WHEN** a manual scan has a hidden probe conflict and the user chooses CANCEL_AND_RUN
- **THEN** the scan starts with those same original options after the hidden probe is canceled
