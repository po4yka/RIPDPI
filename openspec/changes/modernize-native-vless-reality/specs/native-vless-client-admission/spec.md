## Purpose

Keep native VLESS configuration intent intact from import through activation and verify behavior against a real pinned Xray peer.

## ADDED Requirements

### Requirement: REQ-UPSTREAM-INTEROP — Exercise the production client

The interop suite MUST use unmodified pinned Xray code and loopback targets. It MUST check REALITY TCP, Vision, XUDP, admission rejection, and bounded failure. Missing or ignored selected tests MUST fail the runner.

#### Scenario: Native client talks to Xray

- **WHEN** the suite uses the supported profile against its pinned peer
- **THEN** application bytes and datagrams round-trip and invalid authentication or excluded versions deliver no application bytes

### Requirement: REQ-MODERN-IMPORT — Accept equivalent upstream forms

Native import MUST accept supported flat and legacy VLESS settings and equivalent password/publicKey REALITY aliases. It MUST reject conflicting aliases, mixed settings shapes, malformed values, and unsupported options without exposing credential values.

#### Scenario: Modern and legacy input agree

- **WHEN** equivalent profiles use either supported settings shape and key alias
- **THEN** all native profile settings retain the same meaning

### Requirement: REQ-PRESERVE-REJECTED — Preserve durable state

Rejected imports and automatic refreshes MUST preserve stored profiles, credentials, group membership, and active relay settings. A refresh MUST NOT replace a previous group with a partial set after rejecting one of its input nodes.

#### Scenario: Refresh contains a supported and an unsupported node

- **WHEN** an existing subscription refresh contains one accepted node and one rejected node
- **THEN** refresh fails before replacement and all prior durable profile and activation state remains available

### Requirement: REQ-SHARED-CAPABILITIES — Use one admission policy

Native import, profile persistence, editor validation, and service admission MUST use one Kotlin capability model for VLESS security, transport, flow, XHTTP mode, and UDP combinations. Unknown or unavailable combinations MUST fail explicitly rather than normalize to a different requested mode.

#### Scenario: TLS XHTTP requests automatic packet upload

- **WHEN** a TLS XHTTP profile selects auto while packet-up is unavailable
- **THEN** every admission path rejects it and the editor offers supported explicit modes
