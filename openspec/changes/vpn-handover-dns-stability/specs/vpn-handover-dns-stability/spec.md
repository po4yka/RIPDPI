## Purpose

Reduce unnecessary VPN interruption and the wait for encrypted DNS recovery
while preserving the applied route and privacy policy.

## ADDED Requirements

### Requirement: REQ-VPN-LESS-DISRUPTION — Keep unaffected components

The VPN MUST preserve a ready native proxy across same-route metadata changes
when both its effective configuration and its captured inputs match.

#### Scenario: Metadata changes on the same proven physical route

- **WHEN** the activated native path is reusable and the tunnel inputs still match
- **THEN** the same proxy and TUN continue forwarding
- **AND** when only tunnel configuration changes, the proxy remains available during replacement

#### Scenario: Physical route or effective egress changes

- **WHEN** the physical route is different or cannot be proved unchanged, or the egress inputs change
- **THEN** the VPN follows the barrier-preserving replacement path

### Requirement: REQ-VPN-HANDOVER-EVIDENCE — Distinguish readiness and forwarding

The VPN MUST report readiness independently of evidence of forwarding.

#### Scenario: The runtime is ready and the new route has no traffic evidence

- **WHEN** the handover starts forwarding components and no matching post-handover forwarding evidence is available
- **THEN** the service reports runtime readiness without reporting revalidation
- **AND** only current-route forwarding evidence can promote the handover to revalidated

### Requirement: REQ-VPN-DNS-RECOVERY — React to measured DNS failures

Encrypted DNS recovery MUST account for more than one failure in a telemetry
sample and use a bounded pause to retry exhausted candidates.

#### Scenario: Multiple queries fail within one sample

- **WHEN** a current resolver reports enough unresolved failures in one sample
- **THEN** the VPN can select an eligible alternative on that sample

#### Scenario: Alternatives are exhausted

- **WHEN** the retry pause ends and the resolver still has qualifying failure evidence
- **THEN** the VPN can retry eligible alternatives without requiring another failure delta
- **AND** ODoH, offline and ambiguous failure exclusions continue to apply
