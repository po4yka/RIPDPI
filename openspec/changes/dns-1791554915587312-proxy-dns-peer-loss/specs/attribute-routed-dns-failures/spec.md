## Purpose

Attribute encrypted DNS failures to the effective network path before replacing a resolver or rebuilding a tunnel.

## ADDED Requirements

### Requirement: REQ-DNS-ROUTE-ATTRIBUTION — Preserve DNS during shared proxy failure

The service MUST preserve the selected resolver, resolver block store, and native tunnel when an ambiguous transport failure occurs through the effective proxy DNS route. Such a failure alone MUST NOT identify a resolver or SNI block.

#### Scenario: Peer resets DNS during an outage

- **GIVEN** encrypted DNS crosses the active proxy route
- **WHEN** peer loss produces reset, refusal, timeout, broken pipe, or SOCKS transport failure without resolver-specific evidence
- **THEN** the service keeps its resolver and does not rebuild the tunnel solely because of this error
- **AND** it does not persist an SNI block reason for the resolver

#### Scenario: Endpoint failure follows ambiguous route errors

- **GIVEN** ambiguous proxy failures were excluded from resolver attribution
- **WHEN** later resolver-specific evidence arrives
- **THEN** excluded errors do not accumulate toward the resolver failover threshold

### Requirement: REQ-DNS-EFFECTIVE-ROUTE — Use the active DNS route

The service MUST use the active tunnel routing policy for failure attribution, including strict DNS policy and forced routing. A disabled standalone routing preference MUST NOT override the effective proxy route.

#### Scenario: Strict routing overrides the standalone preference

- **GIVEN** strict policy routes encrypted DNS through the proxy and the standalone preference is disabled
- **WHEN** the peer becomes unavailable
- **THEN** failure attribution still treats the DNS request as proxy-routed

### Requirement: REQ-DNS-FAILOVER-COMPATIBILITY — Retain resolver failure handling

The service MUST retain automatic failover for direct DNS failures and resolver-specific evidence. It MUST retain the existing security and routing policies and wire contracts.

#### Scenario: Resolver-specific failure

- **GIVEN** a direct DNS failure or evidence attributable to the resolver endpoint
- **WHEN** the existing automatic failover threshold is met
- **THEN** the service can select an eligible resolver under the existing policy

### Requirement: REQ-DNS-PEER-RECOVERY — Observe real recovery without bypass

Acceptance MUST verify separate-UID payload and DNS traffic through the real Xray TUN route before and after peer loss. It MUST verify failure without direct bypass while the peer is stopped, unchanged resolver and tunnel for ambiguous route errors, server receipts, and cleanup.

#### Scenario: Peer returns after DNS failure

- **GIVEN** the real peer is stopped and a DNS request produces an observed failure
- **WHEN** the peer restarts
- **THEN** payload and DNS receipts prove recovery through the peer
- **AND** resolver identity and real tunnel establishment observations prove that ambiguous failures did not replace them
- **AND** run-owned processes and test state are cleaned up
