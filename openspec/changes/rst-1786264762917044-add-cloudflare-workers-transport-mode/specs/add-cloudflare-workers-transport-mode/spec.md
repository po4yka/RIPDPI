## Purpose

Define the observable completion contract for Add optional Cloudflare Workers transport mode. Add an optional operator-supplied Cloudflare Workers transport mode. The outer TLS metadata uses the Worker hostname, and the Worker forwards an authenticated framed stream to an operator-configured upstream

## ADDED Requirements

### Requirement: REQ-RST-1786264762917044-001 — Operator-supplied Worker URL + auth bearer is consumable via core:data:model ty…

The RIPDPI implementation MUST satisfy this portfolio criterion: Operator-supplied Worker URL + auth bearer is consumable via core:data:model typed schema.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Operator-supplied Worker URL + auth bearer is consumable via core:data:model typed schema

### Requirement: REQ-RST-1786264762917044-002 — WS-tunnel transport variant routes through the Worker, using the Worker hostnam…

The RIPDPI implementation MUST satisfy this portfolio criterion: WS-tunnel transport variant routes through the Worker, using the Worker hostname for SNI and TLS, the real target in a X-Ripdpi-Upstream header.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that WS-tunnel transport variant routes through the Worker, using the Worker hostname for SNI and TLS, the real target in a X-Ripdpi-Upstream header

### Requirement: REQ-RST-1786264762917044-003 — At least one reference Worker script under docs/native/cloudflare-workers/relay…

The RIPDPI implementation MUST satisfy this portfolio criterion: At least one reference Worker script under docs/native/cloudflare-workers/relay.js that operators can deploy.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that At least one reference Worker script under docs/native/cloudflare-workers/relay.js that operators can deploy

### Requirement: REQ-RST-1786264762917044-004 — Loopback test (against a mock HTTP/2 server) exercises the Worker-routed path

The RIPDPI implementation MUST satisfy this portfolio criterion: Loopback test (against a mock HTTP/2 server) exercises the Worker-routed path.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Loopback test (against a mock HTTP/2 server) exercises the Worker-routed path

### Requirement: REQ-RST-1786264762917044-005 — docs/native/cloudflare-tunnel-operations.md documents deployment, cost model, a…

The RIPDPI implementation MUST satisfy this portfolio criterion: docs/native/cloudflare-tunnel-operations.md documents deployment, cost model, and rate-limit considerations.

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that docs/native/cloudflare-tunnel-operations.md documents deployment, cost model, and rate-limit considerations
