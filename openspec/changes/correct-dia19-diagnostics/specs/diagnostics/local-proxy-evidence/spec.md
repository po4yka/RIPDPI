## ADDED Requirements

### Requirement: REQ-DIA19-1 Respect settings and scan limits

The detection runner SHALL require the network switch for active DIA-19
checks and SHALL schedule each enabled check independently. It SHALL
validate custom port ranges and respect the selected range and exclusions.

#### Scenario: API scan only
- **WHEN** network requests and the API scan are on and Proxy scan is off
- **THEN** only the API scan runs on the selected loopback ports

#### Scenario: Active network requests are off
- **WHEN** the user turns off the main network switch
- **THEN** DIA-19 makes no active proxy or API connection or external IP request

### Requirement: REQ-DIA19-2 Validate wire responses and cancel active work

The scanners SHALL connect directly to loopback and apply bounded I/O.
HTTP CONNECT SHALL require a complete final 2xx response. The scanners
SHALL stop in-flight work on cancellation and first match, count actual
completed probes, and retain the reason for negative or incomplete results.

#### Scenario: Peer returns an incomplete success
- **WHEN** the HTTP response has a success status and incomplete headers
- **THEN** the peer is not identified as an HTTP CONNECT proxy

#### Scenario: Scanner receives a permanent denial
- **WHEN** the Xray peer returns an authentication failure
- **THEN** the scanner reports the denial and does not retry that port

### Requirement: REQ-DIA19-3 Limit findings and redact shared data

Local HandlerService reachability SHALL remain local evidence. A remote
endpoint summary SHALL use valid decoded endpoint data. Proc listeners SHALL
count only as indirect evidence. Display and export SHALL apply outbound
privacy rules to typed fields.

#### Scenario: Local API exposes no remote outbound
- **WHEN** ListOutbounds returns only direct and blocked outbounds
- **THEN** the report shows local API reachability and no remote endpoint or hard bypass

#### Scenario: User copies a private report
- **WHEN** privacy mode is on and a report contains Xray outbound metadata
- **THEN** copied and exported data do not expose private outbound identifiers
