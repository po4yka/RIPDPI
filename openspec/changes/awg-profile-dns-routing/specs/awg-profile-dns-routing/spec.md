## Purpose

Keep DNS control traffic for the active standalone AWG profile inside that tunnel while preserving destination blocking and unrelated LAN routing.

## ADDED Requirements

### Requirement: REQ-AWG-DNS-SCOPE — Route exact active resolver traffic

TCP and UDP traffic to exact IPv4 or IPv6 DNS addresses captured from the active standalone AWG profile on ports 53 and 853 MUST use that AWG tunnel when its effective destination policy result is not Block.

#### Scenario: Default LAN exception

- **WHEN** the seeded Direct LAN rule matches a configured AWG resolver address on TCP or UDP port 53 or 853
- **THEN** the effective egress uses the active AWG SOCKS endpoint.

### Requirement: REQ-AWG-DNS-POLICY — Preserve effective blocking and other routing

The system MUST evaluate the existing ordered destination policy first. An effective Block MUST remain Block. Other addresses and other ports MUST retain the original result, including Direct LAN access.

#### Scenario: Effective block

- **WHEN** the original ordered policy returns Block for a configured resolver
- **THEN** no connection or datagram is sent through either direct or AWG egress.

#### Scenario: Unrelated traffic

- **WHEN** traffic targets another LAN address or a configured resolver on another port
- **THEN** its original destination policy result remains unchanged.

### Requirement: REQ-AWG-DNS-AUTHORITY — Freeze active profile authority

Resolver addresses MUST come from the same captured standalone AWG request that starts the runtime. They MUST be validated numeric addresses and copied into the immutable runtime configuration. Remembered configuration MUST NOT grant stale AWG DNS authority when AWG is inactive. Invalid nonempty resolver data or a non-AWG configuration carrying this authority MUST fail closed.

#### Scenario: Profile switch or inactive runtime

- **WHEN** the profile changes or AWG is not active
- **THEN** the next proxy runtime receives only the new profile resolver set or an empty set.

### Requirement: REQ-AWG-DNS-ACCEPTANCE — Verify real tunnel DNS

Completion MUST require an Android artifact from the integrated fix to resolve the existing IPv4 and IPv6 profile DNS through the independent AWG peer without disabling seeded LAN rules or changing fixture addresses.

#### Scenario: Physical verification

- **WHEN** physical acceptance runs against the unchanged private DNS fixture
- **THEN** DNS payload and peer receipts prove transfer through the AWG data path.
