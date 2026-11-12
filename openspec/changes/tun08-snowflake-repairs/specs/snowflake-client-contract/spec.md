## Purpose

Use a verifiable Snowflake client and reject bridge configurations that cannot select a rendezvous method before activation.

## ADDED Requirements

### Requirement: REQ-TUN08-SOURCE — Build the intended Snowflake client

The implementation MUST use an official pinned source that implements the configured DTLS mimic policy for the supported Tor PT path and retain independent obfs4 packaging.

#### Scenario: Build and start the helper

- **WHEN** source assets are built and Snowflake is requested through managed PT v1
- **THEN** the helper declares Snowflake, supports the configured DTLS policy, and its receipt identifies its source and ABI.

### Requirement: REQ-TUN08-RENDEZVOUS — Validate bridge options

The implementation MUST reject missing, malformed or conflicting Snowflake rendezvous options before persistence or activation while retaining valid explicit HTTP, AMP and SQS configurations.

#### Scenario: Validate a bridge

- **WHEN** a bridge lacks a valid HTTP broker URL or a complete nonconflicting SQS configuration
- **THEN** validation rejects it without starting a helper or rewriting stored data.

### Requirement: REQ-TUN08-SCOPE — Preserve Tor-only activation and evidence boundaries

The implementation MUST retain the rejection of standalone Snowflake and unsupported VPN mode and MUST distinguish local validation from unobserved Android or external compatibility.

#### Scenario: Activate a legacy standalone profile

- **WHEN** the profile uses the standalone snowflake relay kind
- **THEN** activation fails with Tor guidance and the saved profile remains readable.
