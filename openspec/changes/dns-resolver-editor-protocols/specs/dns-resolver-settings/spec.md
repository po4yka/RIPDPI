## Purpose

Let users inspect encrypted DNS resolver fields without activating an incomplete or unsupported resolver.

## ADDED Requirements

### Requirement: REQ-DNS-EDITOR-PROTOCOL — Preserve selected protocol

The DNS settings screen MUST show the editor for the selected encrypted DNS protocol. Selecting a custom protocol MUST remain a local draft until valid Save.

#### Scenario: Selecting ODoH while VPN is running

- **WHEN** ODoH is selected before its required fields are complete
- **THEN** the editor shows ODoH fields and neither persisted DNS nor the running service changes

#### Scenario: Stored DoQ resolver

- **WHEN** a saved DoQ resolver opens in DNS settings
- **THEN** the editor shows DoQ fields, allows Save in Proxy mode, and explains the VPN transport limit

#### Scenario: Stored ODoH resolver

- **WHEN** a saved ODoH resolver opens in DNS settings and its endpoint is edited and saved
- **THEN** the editor shows ODoH fields and the saved protocol remains ODoH

### Requirement: REQ-DNS-EDITOR-VALIDATION — Validate endpoint before saving

The DNS settings screen MUST prevent saving an invalid, stale, or incomplete ODoH endpoint. DoQ Save MUST be available in Proxy mode and blocked in VPN mode or while VPN is running. VPN startup MUST reject an effective DoQ resolver before tunnel setup while VPN DNS uses SOCKS5. Config restart paths MUST preserve a running service when saved DoQ cannot start VPN.

An accepted VPN start and a DoQ Save MUST NOT overlap, including the interval before the service publishes a non-Halted status. Completion of one accepted start MUST NOT release another in-flight start reservation.

#### Scenario: Missing ODoH target config

- **WHEN** the ODoH form has no valid target configuration bytes
- **THEN** Save is disabled and the saved resolver remains unchanged

#### Scenario: Expired ODoH target config

- **WHEN** the target config expiry is at or before the current time
- **THEN** Save is disabled and the form explains that fresh config material is required

#### Scenario: DoQ over routed VPN DNS

- **WHEN** DoQ is selected while the VPN DNS path requires SOCKS5
- **THEN** Save is disabled and the current resolver remains active

#### Scenario: DoQ in Proxy mode

- **WHEN** valid DoQ endpoint fields are saved with Proxy mode selected and no VPN running
- **THEN** the saved resolver remains DoQ and a running Proxy service restarts with that resolver

#### Scenario: VPN start with saved DoQ

- **WHEN** the home VPN action is shown with saved DoQ
- **THEN** Start is disabled with a transport explanation and an active VPN retains Stop

#### Scenario: VPN start outside the home screen

- **WHEN** any service entry point requests VPN with effective DoQ DNS
- **THEN** policy resolution rejects startup before VPN tunnel setup

#### Scenario: VPN starts during DoQ Save

- **WHEN** a VPN start is dispatched while a Proxy-mode DoQ DataStore update is in progress
- **THEN** the start is rejected with retry guidance and the DoQ update can complete

#### Scenario: DoQ Save during pending VPN start

- **WHEN** a VPN start was accepted but the service status still reads Halted
- **THEN** DoQ Save is rejected until the matching start command finishes or is canceled

#### Scenario: Overlapping VPN starts finish out of order

- **WHEN** a second accepted VPN start finishes or is rejected while the first start is still running
- **THEN** DoQ Save remains blocked until the first start also finishes or is canceled

#### Scenario: Proxy DoQ scope

- **WHEN** DoQ is saved and Proxy mode runs
- **THEN** RIPDPI can resolve hostnames handled by its local proxy through DoQ, while Android device DNS remains unchanged
