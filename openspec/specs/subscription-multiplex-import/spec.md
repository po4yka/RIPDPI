# subscription-multiplex-import Specification

## Purpose
Prevent subscription import from changing multiplex framing or adding a VLESS flow that the server did not publish.

## Requirements

### Requirement: REQ-MUX-REJECT - Reject enabled multiplex

The importer MUST reject enabled multiplex before creating a selectable profile and MUST return the existing unsupported transport reason with a fixed, non-secret multiplex detail.

#### Scenario: Flow-less REALITY smux profile

- **WHEN** a subscription contains a REALITY outbound with enabled smux and a supported sibling
- **THEN** only the supported sibling is selectable and the rejected node has its original index and label

#### Scenario: Other enabled multiplex modes

- **WHEN** an outbound declares enabled multiplex with an absent or unknown protocol
- **THEN** it is rejected without exposing arbitrary configuration fields in the reason

### Requirement: REQ-MUX-COMPAT - Preserve supported VLESS flow semantics

The importer MUST preserve explicit Vision, empty flow, and omitted no-flow for REALITY outbounds, and MUST allow absent or disabled multiplex.

#### Scenario: Disabled multiplex

- **WHEN** multiplex is disabled on a REALITY outbound with explicit Vision
- **THEN** the profile retains Vision and is not rejected

#### Scenario: No-flow REALITY

- **WHEN** an otherwise supported REALITY outbound omits flow or sets flow to an empty string
- **THEN** the imported flow remains empty
