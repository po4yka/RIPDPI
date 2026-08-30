# add-cloudflare-workers-transport-mode Specification

## Purpose
Define the observable completion contract for Add optional Cloudflare Workers transport mode. Add an optional operator-supplied Cloudflare Workers transport mode. The outer TLS metadata uses the Worker hostname, and the Worker forwards an authenticated framed stream to an operator-configured upstream

## Requirements

### Requirement: REQ-RST-1786264762917044-001 — Typed and secret-safe Worker configuration

RIPDPI MUST expose a typed `core:data:model` configuration containing the operator-supplied Worker URL and auth bearer. AppSettings MUST persist only the URL and a credential reference. The bearer MUST be loaded from Android-Keystore-backed storage, redacted from debug output, and excluded from settings export, backup, remembered-policy, and policy-signature JSON.

#### Scenario: Verify criterion 1

- **WHEN** a Worker URL and matching secure-store bearer are resolved for a proxy session
- **THEN** the typed runtime configuration MUST contain both values
- **AND** durable settings and remembered-policy JSON MUST NOT contain the bearer

### Requirement: REQ-RST-1786264762917044-002 — Optional Worker route preserves TLS identity and target confinement

When Worker configuration is present, the Telegram WS tunnel MUST resolve and dial the Worker endpoint, verify TLS for the Worker hostname, use that hostname for SNI, URI authority, and `Host`, send `Authorization: Bearer <secret>`, and send the canonical detected Telegram gateway URL in `X-Ripdpi-Upstream`. Without Worker configuration, the existing direct gateway request MUST remain unchanged.

#### Scenario: Verify criterion 2

- **WHEN** Worker mode is configured for a validated Telegram data-center connection
- **THEN** the outer TLS and WebSocket request MUST target the Worker URL
- **AND** `X-Ripdpi-Upstream` MUST contain only the canonical `wss://kws{dc}.web.telegram.org/apiws` value
- **AND** partial configuration, unsafe URLs, control characters, or simultaneous fake-SNI MUST fail before network I/O

### Requirement: REQ-RST-1786264762917044-003 — At least one reference Worker script under docs/native/cloudflare-workers/relay…

The RIPDPI implementation MUST satisfy this portfolio criterion: At least one reference Worker script under docs/native/cloudflare-workers/relay.js that operators can deploy.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that At least one reference Worker script under docs/native/cloudflare-workers/relay.js that operators can deploy

### Requirement: REQ-RST-1786264762917044-004 — Production-protocol loopback exercises the Worker route

The Worker-routed path MUST be exercised against a local TLS WebSocket edge using the same RFC 6455 upgrade and binary framing as production.

#### Scenario: Verify criterion 4

- **WHEN** the client connects to the mock Worker edge and relays a framed payload
- **THEN** the edge MUST observe the Worker `Host`, bearer, and canonical upstream headers
- **AND** the payload MUST round-trip through WebSocket binary frames

### Requirement: REQ-RST-1786264762917044-005 — docs/native/cloudflare-tunnel-operations.md documents deployment, cost model, a…

The RIPDPI implementation MUST satisfy this portfolio criterion: docs/native/cloudflare-tunnel-operations.md documents deployment, cost model, and rate-limit considerations.

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that docs/native/cloudflare-tunnel-operations.md documents deployment, cost model, and rate-limit considerations
