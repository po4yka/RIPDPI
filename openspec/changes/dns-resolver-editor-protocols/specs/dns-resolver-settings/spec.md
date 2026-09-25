## Purpose

Let users inspect and edit the complete encrypted DNS resolver that is active on their VPN connection, including DoQ and ODoH, without accidental protocol changes.

## ADDED Requirements

### Requirement: REQ-DNS-EDITOR-PROTOCOL — Preserve selected protocol

The DNS settings screen MUST show an editor for the saved encrypted DNS protocol and MUST save through that protocol's settings mutation.

#### Scenario: Stored DoQ resolver

- **WHEN** a saved DoQ resolver opens in DNS settings and its endpoint is edited and saved
- **THEN** the editor shows DoQ fields and the saved protocol remains DoQ

#### Scenario: Stored ODoH resolver

- **WHEN** a saved ODoH resolver opens in DNS settings and its endpoint is edited and saved
- **THEN** the editor shows ODoH fields and the saved protocol remains ODoH

### Requirement: REQ-DNS-EDITOR-VALIDATION — Validate endpoint before saving

The DNS settings screen MUST prevent saving an invalid or incomplete DoQ or ODoH endpoint.

#### Scenario: Missing ODoH target config

- **WHEN** the ODoH form has no valid target configuration bytes
- **THEN** Save is disabled and the saved resolver remains unchanged
