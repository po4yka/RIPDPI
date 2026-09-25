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

The DNS settings screen MUST prevent saving an invalid, stale, or incomplete ODoH endpoint. DoQ Save MUST be available in Proxy mode and blocked in VPN mode or while VPN is running. VPN start with saved DoQ MUST be blocked on the home path while VPN DNS uses a SOCKS5 transport.

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
