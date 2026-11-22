## ADDED Requirements

### Requirement: REQ-DGN-1795354722978192-001 — Explain local physical network conditions

Home diagnostics SHALL display sign-in and repeat instructions for a confirmed captive portal on the measured physical network. Home SHALL display a separate explanation when Android has not validated that network and no portal is detected. Validated networks and unknown physical state SHALL have no local network notice.

#### Scenario: Android detects a captive portal

- **WHEN** the selected physical network reports a captive portal
- **THEN** the Home results explain how to sign in and run the check again

#### Scenario: Android has not validated the network

- **WHEN** Android reports no portal and has not validated Internet access
- **THEN** Home displays the validation observation

### Requirement: REQ-DGN-1795354722978192-002 — Use current network evidence

Home SHALL omit the local network notice when the run loses authority for its original network or the measured fingerprint cannot be matched to the current network.

#### Scenario: The network changes during diagnostics

- **WHEN** the physical network generation changes before the Home report is published
- **THEN** Home discards the earlier local network condition

#### Scenario: The network changes with Home results open

- **WHEN** the physical network changes while Home displays a completed report
- **THEN** Home removes the notice from the previous network
