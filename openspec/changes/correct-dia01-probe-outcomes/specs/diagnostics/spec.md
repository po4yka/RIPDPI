## ADDED Requirements

### Requirement: REQ-DIA01-CONTROL — Bound transport probe work

A scan MUST stop active TCP, TLS, and WebSocket waits when it cancels or reaches
its deadline. It MUST distinguish an administrative stop from a network failure.

#### Scenario: Stop an active upgrade

- **WHEN** a scan cancels or expires while an endpoint sends the Upgrade response slowly
- **THEN** the connector closes the probe socket, and the scan records no success or network failure for the interrupted probe

### Requirement: REQ-DIA01-WS — Honor the WebSocket target

A direct WSS probe MUST try eligible alternate TCP peers within the opening
budget. Direct and SOCKS5 paths MUST use the configured HTTP authority, TLS
identity, and opening budget. The supported profile MUST reject incompatible
target settings before it starts the connection.

#### Scenario: Open an alternate direct peer

- **WHEN** the first resolved peer refuses TCP and an alternate peer accepts it
- **THEN** the probe can reach TLS and Upgrade through the alternate peer with the original authority

#### Scenario: Check subprotocol agreement

- **WHEN** the endpoint returns a valid Upgrade without selecting a subprotocol
- **THEN** a generic probe accepts it, and a profile that requires a subprotocol reports a separate negotiation result

### Requirement: REQ-DIA01-TLS — Keep TLS facts distinct

The report MUST distinguish a processed ServerHello, a received alert of unknown
origin, and a local server-certificate verification failure.

#### Scenario: Receive an alert before ServerHello

- **WHEN** a probe receives only a TLS alert
- **THEN** the report carries the alert code without a ServerHello fact or an attribution of the alert source

### Requirement: REQ-DIA01-PATH — Report the measured path

An IN_PATH Web probe that sends a hostname to SOCKS5 MUST NOT make an additional
system lookup just to populate destination evidence. TTL and timing MUST identify
the observed socket and the measured phase.

#### Scenario: Record local IPv4 socket TTL

- **WHEN** a probe has a local IPv4 socket
- **THEN** its TTL is labeled as local and associated with the direct or client-to-proxy leg
- **AND** an IPv6 socket does not produce an IPv4 TTL observation

#### Scenario: Measure a full transport opening

- **WHEN** a measurement includes lookup, retries, or proxy negotiation
- **THEN** the report labels it as elapsed opening time
