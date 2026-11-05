## Purpose

Keep diagnostics conclusions and applied settings within the evidence collected by the measured network path.

## ADDED Requirements

### Requirement: REQ-DGN-COMPOSE — Preserve measured strategy configuration

The system MUST compose recommended settings from the actual successful lane winners and reject inconsistent recommendations before application.

#### Scenario: Both lanes have winners

- **WHEN** a TCP candidate and a QUIC candidate succeed
- **THEN** the recommended configuration contains both measured lane settings and the reported winner identities match those settings.

#### Scenario: Recommendation does not match its winner

- **WHEN** the supplied configuration has a different strategy family from a claimed winner
- **THEN** neither Home application nor background persistence applies that recommendation.

### Requirement: REQ-DGN-VERIFY — Confirm access from complete scoped evidence

The system MUST confirm access only from a complete, successful verification report for the measured path and network scope.

#### Scenario: Verification is cancelled

- **WHEN** an interrupted report contains a successful TLS result
- **THEN** verification remains incomplete and does not confirm VPN access.

#### Scenario: Adverse evidence accompanies success

- **WHEN** a verification report contains a certificate failure or block page as well as a successful connection
- **THEN** the summary preserves the failure and does not confirm unrestricted access.

### Requirement: REQ-DGN-SUITE — Preserve uncertain probe outcomes

The DPI suite MUST keep unavailable, skipped, and inconclusive measurements distinct from clean results and supported interference findings.

#### Scenario: QUIC is unavailable

- **WHEN** the selected QUIC checks all time out
- **THEN** the suite reports an inconclusive result, not a clean result.

#### Scenario: A domain is unreachable

- **WHEN** a domain cannot be reached without independent interference evidence
- **THEN** the suite does not claim DPI detection.

### Requirement: REQ-DGN-LANES — Preserve independent transport recommendations

The system MUST represent each missing lane winner as absent, retain verified recommendations for the other lane, and preserve current settings for an unmeasured lane when a partial recommendation is applied.

#### Scenario: Only TCP succeeds

- **WHEN** a TCP strategy succeeds and no QUIC candidate succeeds
- **THEN** the report retains the TCP recommendation with no QUIC winner.

#### Scenario: QUIC-first execution skips TCP

- **WHEN** a confirmed QUIC result permits the quick plan to skip TCP
- **THEN** the report can retain the QUIC recommendation and transport-pivot evidence without inventing a TCP winner.

### Requirement: REQ-DGN-COMPARE — Compare matching protocol and service evidence

Path comparison MUST retain normalized protocol outcomes and distinguish partial service availability from complete service success.

#### Scenario: Server supports one TLS version

- **WHEN** verified TLS 1.3 works and the server rejects TLS 1.2 with a protocol-version response
- **THEN** comparison does not classify that domain as blocked because of TLS 1.2 alone.

#### Scenario: Bootstrap works but service handshake fails

- **WHEN** a service bootstrap endpoint works but its tested handshake or media component fails
- **THEN** comparison preserves that component failure and does not mark the whole service successful.

### Requirement: REQ-DGN-SNI — Require completed alternative-SNI evidence

The system MUST report alternative-SNI recovery only after a successful complete measurement.

#### Scenario: Alternative SNI returns an HTTP refusal

- **WHEN** an alternative SNI returns HTTP 403, 431, or a server error
- **THEN** receiving that response alone does not establish recovered access.

### Requirement: REQ-DGN-TRANSFER — Use suitable transfer evidence

Throughput diagnostics MUST use targets suitable for the required transfer window, preserve short complete responses as insufficient measurement evidence, and scope failure suppression to relevant targets and controls.

#### Scenario: Complete response is too small

- **WHEN** a successful HTTP response ends before the required transfer window
- **THEN** the result describes insufficient measurement evidence and does not claim a network failure.

#### Scenario: Unrelated domain fails

- **WHEN** the measured throughput target and control complete but an unrelated domain times out
- **THEN** the unrelated failure does not remove the valid throughput comparison.

### Requirement: REQ-DGN-RESET — Preserve observed TCP failure scope

The system MUST distinguish observed reset, EOF, and transfer cutoff from an unverified injected packet, packet origin, or TCP receive-window measurement.

#### Scenario: Server closes immediately

- **WHEN** a connection closes within two connection RTTs without independent packet-origin evidence
- **THEN** the result does not assert an injected in-path reset or a measured receive window.
