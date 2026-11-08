# transparent-tcp-ingress Specification

## Purpose
Keep raw TCP ingress consistent with destination policy, Linux socket address identity and the proxy runtime shutdown boundary.

## Requirements

### Requirement: REQ-LOC07-IPV6 — Enforce Transparent destination family policy

Transparent MUST reject a canonical IPv6 destination before egress when network IPv6 support is disabled. IPv4-mapped IPv6 addresses SHALL use their canonical IPv4 family. This setting controls the logical destination, not a separately configured relay carrier.

#### Scenario: IPv6 is disabled

- **GIVEN** Transparent has IPv6 disabled
- **WHEN** the recovered target is IPv6
- **THEN** the client closes with no proxy reply and no upstream connection.

#### Scenario: Canonical IPv4 remains allowed

- **GIVEN** IPv6 is disabled
- **WHEN** the recovered target is IPv4 or IPv4-mapped IPv6
- **THEN** the existing IPv4 admission and raw relay rules still apply.

### Requirement: REQ-LOC07-SCOPE — Retain IPv6 zone identity

The Linux adapter MUST retain sin6_scope_id from a valid IPv6 original-destination sockaddr. Unscoped IPv6 and IPv4 conversion MUST remain valid.

#### Scenario: Scoped kernel destination

- **WHEN** a kernel sockaddr contains a link-local address with scope ID 42 and port 8443
- **THEN** the resulting destination retains address, scope ID and port.

### Requirement: REQ-LOC07-SELF — Match the actual listener coverage

The TCP guard MUST reject destinations covered by the actual listening socket. It MUST NOT classify a separate local service as self only because the port matches. Remote-peer access to device-local targets MUST remain denied.

#### Scenario: Separate local service

- **GIVEN** a listener is bound to one exact local address
- **WHEN** a loopback client targets a separate listener on another local address with the same port
- **THEN** the separate service can receive the raw connection.

#### Scenario: Wildcard and dual-stack listener

- **WHEN** a target address and port are covered by the actual wildcard or dual-stack listener
- **THEN** the request closes before a self connection is opened.

### Requirement: REQ-LOC07-CANCEL — Cancel pending TCP connect

A pending TCP connect MUST observe runtime shutdown within the existing forced drain budget, close its socket, and release its worker and client slot. It MUST preserve connect deadlines, protection, TCP options and observer events. A shutdown cancellation MUST NOT produce a failed-connect quality signal or route fallback. Genuine connection failures MUST remain visible.

#### Scenario: Shutdown with a pending connection

- **GIVEN** a direct TCP connect remains pending and its connect timeout exceeds the drain budget
- **WHEN** runtime shutdown begins
- **THEN** no worker or socket remains owned by the pending attempt after the forced drain window and no late relay starts.

### Requirement: REQ-LOC07-PRESERVE — Preserve ingress boundaries

Transparent MUST send no SOCKS or HTTP success reply. It MUST retain the loopback-only and incompatible-token checks and fail closed when original-destination lookup is unavailable. This change SHALL NOT install redirect rules or enable root mode.

#### Scenario: Unsupported platform

- **WHEN** original-destination lookup is unsupported
- **THEN** the connection closes without application or authentication data sent to an upstream.
